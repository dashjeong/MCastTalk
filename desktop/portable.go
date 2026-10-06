package main

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"debug/pe"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	bolt "go.etcd.io/bbolt"
)

const portableManifestName = "portable-manifest.json"
const portableMaxManifest = 8 << 20
const portableMaxFiles = 16384
const portableMaxFile int64 = 128 << 30
const portableMaxTotal int64 = 512 << 30
const portableDiskReserve int64 = 512 << 20

type PortableExportOptions struct {
	Format           string   `json:"format"`
	AssetIDs         []string `json:"assetIDs"`
	IncludeEXE       bool     `json:"includeEXE"`
	IncludeKnowledge bool     `json:"includeKnowledge"`
}
type PortableReport struct {
	Installed       []InstalledAsset `json:"installed"`
	Bytes           int64            `json:"bytes"`
	TargetVerified  bool             `json:"targetVerified"`
	RequiresWarmup  bool             `json:"requiresWarmup"`
	OfflineComplete bool             `json:"offlineComplete"`
	Warnings        []string         `json:"warnings"`
}
type PortableInspection struct {
	Manifest          portableManifest `json:"manifest"`
	Compatible        bool             `json:"compatible"`
	RequiredDiskBytes int64            `json:"requiredDiskBytes"`
	Diagnostic        Diagnostic       `json:"diagnostic"`
	Warnings          []string         `json:"warnings"`
	Fingerprint       string           `json:"fingerprint"`
}
type portableConfig struct {
	SourceLanguage   string   `json:"sourceLanguage"`
	TargetLanguages  []string `json:"targetLanguages"`
	TranslationModel string   `json:"translationModel"`
	STTModel         string   `json:"sttModel"`
	MaxListeners     int      `json:"maxListeners"`
	PreferredBackend string   `json:"preferredBackend"`
}
type portableAsset struct {
	Profile Artifact `json:"profile"`
	Root    string   `json:"root"`
}

// Optional in schema 1, so older model-only packages remain readable. These
// files are installation caches, not assets that can be started by the engine.
type portableSystemDependency struct {
	Profile SystemDependency `json:"profile"`
	Path    string           `json:"path"`
}
type portableFile struct {
	Path    string `json:"path"`
	AssetID string `json:"assetID,omitempty"`
	Bytes   int64  `json:"bytes"`
	SHA256  string `json:"sha256"`
}
type portableManifest struct {
	Schema             int                        `json:"schema"`
	AppVersion         string                     `json:"appVersion"`
	TargetOS           string                     `json:"targetOS"`
	TargetArch         string                     `json:"targetArch"`
	CreatedAt          time.Time                  `json:"createdAt"`
	Assets             []portableAsset            `json:"assets"`
	Files              []portableFile             `json:"files"`
	SafeConfig         portableConfig             `json:"safeConfig"`
	KnowledgeIncluded  bool                       `json:"knowledgeIncluded"`
	ExecutableIncluded bool                       `json:"executableIncluded"`
	SystemDependencies []portableSystemDependency `json:"systemDependencies,omitempty"`
}
type portableScript struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Text  string `json:"text"`
}
type portableKnowledge struct {
	Terms   []GlossaryTerm   `json:"terms"`
	Scripts []portableScript `json:"scripts"`
	Lessons []Lesson         `json:"lessons"`
}
type PortableManager struct {
	store                *Store
	assets               *AssetManager
	mu                   sync.Mutex
	targetOS, targetArch string
	diskAvailable        func() int64      // tests only; production uses measured target diagnostics
	beforeCommit         func() error      // fault injection; never populated by production
	bundleTreePins       map[string]string // tests only; production always uses immutable catalogue pins
	progress             func(string, string, int64, int64)
	expectedManifestSHA  string
}

func (p *PortableManager) note(phase, file string, done, total int64) {
	if p.progress != nil {
		p.progress(phase, file, done, total)
	}
}

func NewPortableManager(s *Store, a *AssetManager) *PortableManager {
	return &PortableManager{store: s, assets: a, targetOS: runtime.GOOS, targetArch: runtime.GOARCH}
}
func portableName(n string) bool {
	if n == "" || len(n) > 1024 || strings.ContainsAny(n, "\\:\x00<>\"|?*") || strings.HasPrefix(n, "/") {
		return false
	}
	for _, c := range n {
		if c < 32 {
			return false
		}
	}
	for _, part := range strings.Split(n, "/") {
		base := strings.ToUpper(strings.TrimRight(strings.SplitN(part, ".", 2)[0], " "))
		device := reservedNames.MatchString(base) || base == "CONIN$" || base == "CONOUT$" || regexp.MustCompile(`^(COM|LPT)[¹²³]$`).MatchString(base)
		if part == "" || part == "." || part == ".." || len(part) > 240 || strings.HasSuffix(part, ".") || strings.HasSuffix(part, " ") || device {
			return false
		}
	}
	return true
}

// Check every ancestor, including ancestors outside the managed directory.
func portableLocalPath(path string, directory bool) error {
	if !filepath.IsAbs(path) {
		return errors.New("전체 파일 경로를 입력하세요")
	}
	for cur := filepath.Clean(path); ; cur = filepath.Dir(cur) {
		st, err := os.Lstat(cur)
		if err != nil {
			return err
		}
		if st.Mode()&os.ModeSymlink != 0 || st.Mode()&os.ModeIrregular != 0 || portableReparse(st) {
			return errors.New("링크 또는 특수 경로는 사용할 수 없습니다")
		}
		if cur == filepath.Clean(path) {
			if directory && !st.IsDir() || !directory && !st.Mode().IsRegular() {
				return errors.New("일반 파일 또는 폴더가 필요합니다")
			}
		} else if !st.IsDir() {
			return errors.New("잘못된 상위 폴더")
		}
		if filepath.Dir(cur) == cur {
			break
		}
	}
	return nil
}
func portableProfile(a Artifact) error {
	for _, b := range Catalog() {
		if a.ID == b.ID {
			// Schema-1 packages made before tree pins still need the complete
			// source tree to match today's pin. Only the absent field is tolerated.
			if a.TreeSHA256 == "" && artifactBundle(b) {
				b.TreeSHA256 = ""
			}
			if !reflect.DeepEqual(a, b) {
				return errors.New("내장 프로필의 고정 정보가 변경되었습니다")
			}
			return nil
		}
	}
	if !strings.HasPrefix(a.ID, "custom-") || !a.Experimental || a.Bytes <= 4 || a.Bytes > portableMaxFile || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(a.SHA256) {
		return errors.New("유효한 모델 프로필이 필요합니다")
	}
	if !(a.Task == "translation" && a.Format == "gguf" && (a.Prompt == "chat" || a.Prompt == "translategemma") || a.Task == "stt" && a.Format == "ggml" && a.Family == "whisper") {
		return errors.New("사용자 모델의 구동 형식을 지원하지 않습니다")
	}
	u, e := url.Parse(a.URL)
	if e != nil || u.Scheme != "https" || u.Host != "huggingface.co" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawPath != "" {
		return errors.New("고정 Hugging Face 주소가 필요합니다")
	}
	parts := strings.Split(strings.TrimPrefix(u.Path, "/"), "/")
	if len(parts) < 5 || parts[2] != "resolve" || !isValidRepoName(parts[0]+"/"+parts[1]) || !regexp.MustCompile(`^[a-f0-9]{40}$`).MatchString(parts[3]) || !portableName(strings.Join(parts[4:], "/")) {
		return errors.New("고정 HF 커밋과 파일이 필요합니다")
	}
	repo, rev, name := parts[0]+"/"+parts[1], parts[3], strings.Join(parts[4:], "/")
	sum := sha256.Sum256([]byte(repo + "/" + rev + "/" + name))
	if a.ID != "custom-"+hex.EncodeToString(sum[:10]) || a.Source != repo+"@"+rev {
		return errors.New("모델 프로필의 식별자가 일치하지 않습니다")
	}
	if a.RAMGB < 1 || a.RAMGB > 512 || len(a.Name) > 512 || len(a.License) > 1024 || len(a.Backends) > 8 {
		return errors.New("모델 메타데이터 범위 오류")
	}
	return nil
}

func (p *PortableManager) trustedBundleProfile(a Artifact) Artifact {
	if a.TreeSHA256 == "" {
		for _, fixed := range Catalog() {
			if fixed.ID == a.ID {
				a.TreeSHA256 = fixed.TreeSHA256
				break
			}
		}
	}
	if pin, ok := p.bundleTreePins[a.ID]; ok {
		a.TreeSHA256 = pin
	}
	return a
}

// Verify package metadata against the immutable archive tree before inspection
// claims compatibility or import writes files. The installed self-manifest and
// the portable list must independently describe that same complete tree.
func (p *PortableManager) verifyPortableBundlePins(ctx context.Context, s *portableSource) error {
	for _, a := range s.manifest.Assets {
		if !artifactBundle(a.Profile) {
			continue
		}
		profile := p.trustedBundleProfile(a.Profile)
		if !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(profile.TreeSHA256) {
			return errors.New("모델 번들의 공식 설치파일 해시가 필요합니다")
		}
		m := bundleManifest{}
		manifestName := a.Root + "/installed-manifest.json"
		for _, f := range s.manifest.Files {
			if f.AssetID == a.Profile.ID && f.Path != manifestName {
				m.Files = append(m.Files, bundleFile{strings.TrimPrefix(f.Path, a.Root+"/"), f.SHA256, f.Bytes})
			}
		}
		sha, err := canonicalBundleTreeSHA(m)
		if err != nil || sha != profile.TreeSHA256 {
			return errors.New("패키지 번들 파일 목록이 공식 원본의 고정 해시와 다릅니다")
		}
		op, ok := s.entries[manifestName]
		if !ok {
			return errors.New("번들 설치파일 목록이 없습니다")
		}
		r, err := op()
		if err != nil {
			return err
		}
		b, err := io.ReadAll(io.LimitReader(engineContextReader{ctx, r}, bundleMaxManifest+1))
		ce := r.Close()
		if err != nil {
			return err
		}
		if ce != nil {
			return ce
		}
		m = bundleManifest{}
		if len(b) > bundleMaxManifest || portableJSON(b, &m) != nil {
			return errors.New("번들 설치파일 목록 형식 오류")
		}
		sha, err = canonicalBundleTreeSHA(m)
		if err != nil || sha != profile.TreeSHA256 {
			return errors.New("번들 설치파일 목록이 공식 원본의 고정 해시와 다릅니다")
		}
	}
	return ctx.Err()
}
func portableWarnings(m portableManifest) []string {
	w := []string{"SHA-256은 파일 무결성을 확인합니다. 패키지 배포자의 신원을 인증하지 않습니다. 신뢰하는 PC에서 만든 패키지만 가져오세요.", "대상 PC의 GPU·NPU 드라이버, Windows 음성 및 모델 지연·품질은 다시 검증해야 합니다.", "인터넷은 필요하지 않습니다. 모바일 연결용 로컬 네트워크와 HTTPS 인증서는 대상 PC에서 구성하세요."}
	if !portableModelsComplete(m) {
		w = append(w, "CPU 대체 런타임이 빠져 있습니다. 오프라인 환경에서 엔진을 시작하지 못할 수 있습니다.")
	}
	if !portableTTSComplete(m) {
		w = append(w, "선택한 언어의 번들 음성 모델 또는 TTS 런타임이 빠져 있습니다. 이전 모델 전용 패키지는 가져올 수 있지만 오프라인 음성 통번역 환경은 미완비입니다.")
	}
	for _, dep := range SystemDependencies() {
		found := false
		for _, included := range m.SystemDependencies {
			found = found || systemDependencyProfileCompatible(included.Profile, dep)
		}
		if !found {
			w = append(w, "Microsoft VC++ 설치 캐시가 빠져 있습니다 ("+dep.ID+"). 대상 PC에 필요한 DLL이 없다면 인터넷 없이 환경을 준비하지 못할 수 있습니다.")
		}
	}
	if len(m.SystemDependencies) != 0 {
		w = append(w, "Microsoft VC++ 설치 파일은 캐시로만 보관됩니다. 대상 Windows에서 오프라인 서명 신뢰·DLL 버전 확인과 별도 설치 동의가 필요하며 자동 설치하지 않습니다.")
	}
	return w
}
func portableModelsComplete(m portableManifest) bool {
	ids := map[string]bool{}
	for _, a := range m.Assets {
		ids[a.Profile.ID] = true
	}
	return ids[m.SafeConfig.TranslationModel] && ids[m.SafeConfig.STTModel] && ids["runtime-llama-cpu"] && ids["runtime-whisper-cpu"]
}
func portableComplete(m portableManifest) bool {
	if !portableModelsComplete(m) || !portableTTSComplete(m) {
		return false
	}
	for _, dep := range SystemDependencies() {
		found := false
		for _, included := range m.SystemDependencies {
			if systemDependencyProfileCompatible(included.Profile, dep) {
				found = true
			}
		}
		if !found {
			return false
		}
	}
	return true
}

func portableTTSComplete(m portableManifest) bool {
	langs := append([]string{m.SafeConfig.SourceLanguage}, m.SafeConfig.TargetLanguages...)
	if !bundledTTSLanguagesSupported(langs) {
		return false
	}
	ids := map[string]bool{}
	for _, a := range m.Assets {
		ids[a.Profile.ID] = true
	}
	for _, id := range bundledTTSAssetIDs(langs) {
		if !ids[id] {
			return false
		}
	}
	return true
}

func portableDependencyPath(dep SystemDependency) string {
	return "dependencies/" + dep.ID + "-" + dep.SHA256 + ".exe"
}
func portableDependencyProfile(dep SystemDependency) error {
	for _, fixed := range SystemDependencies() {
		if dep.ID == fixed.ID {
			if !systemDependencyProfileCompatible(dep, fixed) {
				return errors.New("시스템 구성요소의 고정 프로필이 변경되었습니다")
			}
			return nil
		}
	}
	return errors.New("허용되지 않은 시스템 구성요소입니다")
}
func portableDependencyForFile(m portableManifest, f portableFile) (SystemDependency, bool) {
	for _, dep := range m.SystemDependencies {
		if f.AssetID == "" && f.Path == dep.Path {
			return dep.Profile, true
		}
	}
	return SystemDependency{}, false
}
func portableDependencyPE(path string, dep SystemDependency) error {
	if e := portableDependencyProfile(dep); e != nil {
		return e
	}
	f, e := pe.Open(path)
	if e != nil {
		return fmt.Errorf("시스템 설치 파일의 PE 형식 오류: %w", e)
	}
	defer f.Close()
	// This pinned x64 bundle uses an I386 installer bootstrap. No other
	// application or runtime receives this architecture exception.
	if f.Machine != pe.IMAGE_FILE_MACHINE_I386 {
		return errors.New("고정 Microsoft 설치 파일의 아키텍처가 다릅니다")
	}
	return nil
}
func portableJSON(data []byte, dst any) error {
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if e := d.Decode(dst); e != nil {
		return e
	}
	var tail any
	if d.Decode(&tail) != io.EOF {
		return errors.New("JSON 형식 오류")
	}
	return nil
}
func portablePE(path string) error {
	f, e := pe.Open(path)
	if e != nil {
		return fmt.Errorf("Windows PE 형식 오류: %w", e)
	}
	defer f.Close()
	if f.Machine != pe.IMAGE_FILE_MACHINE_AMD64 {
		return errors.New("Windows x64 실행파일이 필요합니다")
	}
	return nil
}

// Call only with registered metadata after the streaming copy verifies SHA-256.
func portableModelHeader(path string, a Artifact) error {
	if e := portableLocalPath(path, false); e != nil {
		return e
	}
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	st, e := f.Stat()
	if e != nil {
		return e
	}
	header := make([]byte, 4)
	if _, e = io.ReadFull(f, header); e != nil {
		return e
	}
	magic := "GGUF"
	if a.Format == "ggml" {
		magic = "lmgg"
	}
	if st.Size() != a.Bytes || string(header) != magic {
		return errors.New("모델 크기 또는 파일 형식이 다릅니다")
	}
	return nil
}

type portableSource struct {
	manifest    portableManifest
	entries     map[string]func() (io.ReadCloser, error)
	close       func() error
	fingerprint string
}

func openPortable(path string) (*portableSource, error) {
	st, e := os.Lstat(path)
	if e != nil {
		return nil, e
	}
	if e = portableLocalPath(path, st.IsDir()); e != nil {
		return nil, e
	}
	s := &portableSource{entries: map[string]func() (io.ReadCloser, error){}, close: func() error { return nil }}
	sizes := map[string]int64{}
	if st.IsDir() {
		e = filepath.Walk(path, func(p string, info os.FileInfo, err error) error {
			if err != nil {
				return err
			}
			if e := portableLocalPath(p, info.IsDir()); e != nil {
				return e
			}
			if p == path {
				return nil
			}
			rel, _ := filepath.Rel(path, p)
			name := filepath.ToSlash(rel)
			if !portableName(name) {
				return errors.New("안전하지 않은 폴더 경로")
			}
			if info.IsDir() {
				return nil
			}
			if len(s.entries) >= portableMaxFiles+1 {
				return errors.New("파일 수 초과")
			}
			filePath := p
			s.entries[name] = func() (io.ReadCloser, error) {
				if e := portableLocalPath(filePath, false); e != nil {
					return nil, e
				}
				return os.Open(filePath)
			}
			sizes[name] = info.Size()
			return nil
		})
	} else {
		var z *zip.ReadCloser
		z, e = zip.OpenReader(path)
		if e == nil {
			s.close = z.Close
			for _, f := range z.File {
				if !portableName(f.Name) || !f.Mode().IsRegular() || (f.Method != zip.Store && f.Method != zip.Deflate) || f.UncompressedSize64 > uint64(portableMaxFile) || len(s.entries) >= portableMaxFiles+1 {
					e = errors.New("안전하지 않은 ZIP 파일")
					break
				}
				if _, ok := s.entries[f.Name]; ok {
					e = errors.New("중복 ZIP 파일")
					break
				}
				if f.Method == zip.Deflate && f.UncompressedSize64 > uint64(portableMaxManifest) {
					e = errors.New("큰 모델은 ZIP Store 형식으로 내보내세요")
					break
				}
				entry := f
				s.entries[f.Name] = entry.Open
				sizes[f.Name] = int64(f.UncompressedSize64)
			}
		}
	}
	fail := func(err error) (*portableSource, error) { _ = s.close(); return nil, err }
	if e != nil {
		return fail(e)
	}
	op, ok := s.entries[portableManifestName]
	if !ok || sizes[portableManifestName] > portableMaxManifest {
		return fail(errors.New("패키지 매니페스트가 없거나 너무 큽니다"))
	}
	r, e := op()
	if e != nil {
		return fail(e)
	}
	b, e := io.ReadAll(io.LimitReader(r, portableMaxManifest+1))
	ce := r.Close()
	if e != nil {
		return fail(e)
	}
	if ce != nil {
		return fail(ce)
	}
	if len(b) > portableMaxManifest {
		return fail(errors.New("매니페스트 크기 초과"))
	}
	if e = portableJSON(b, &s.manifest); e != nil {
		return fail(e)
	}
	sum := sha256.Sum256(b)
	s.fingerprint = hex.EncodeToString(sum[:])
	if e = validatePortableManifest(s.manifest, s.entries, sizes); e != nil {
		return fail(e)
	}
	return s, nil
}
func validatePortableManifest(m portableManifest, entries map[string]func() (io.ReadCloser, error), sizes map[string]int64) error {
	if m.Schema != 1 || m.AppVersion != Version || m.TargetOS != "windows" || m.TargetArch != "amd64" || m.CreatedAt.IsZero() {
		return errors.New("패키지 버전 또는 플랫폼이 호환되지 않습니다")
	}
	if len(m.Assets) < 2 || len(m.Assets) > 512 || len(m.Files) < 2 || len(m.Files) > portableMaxFiles || len(entries) != len(m.Files)+1 {
		return errors.New("패키지 파일 목록 불일치")
	}
	ids := map[string]portableAsset{}
	for _, a := range m.Assets {
		if e := portableProfile(a.Profile); e != nil {
			return e
		}
		if _, ok := ids[a.Profile.ID]; ok {
			return errors.New("중복 모델 프로필")
		}
		folder := "models/"
		if a.Profile.Task == "runtime" {
			folder = "runtimes/"
		}
		if a.Root != folder+a.Profile.ID || !portableName(a.Root) {
			return errors.New("프로필 경로 오류")
		}
		ids[a.Profile.ID] = a
	}
	dependencies := map[string]portableSystemDependency{}
	dependencyIDs := map[string]bool{}
	if len(m.SystemDependencies) > len(SystemDependencies()) {
		return errors.New("시스템 구성요소 목록이 고정 범위를 넘습니다")
	}
	for _, dep := range m.SystemDependencies {
		if e := portableDependencyProfile(dep.Profile); e != nil {
			return e
		}
		if dep.Path != portableDependencyPath(dep.Profile) || !portableName(dep.Path) || dependencyIDs[dep.Profile.ID] {
			return errors.New("시스템 구성요소 경로 또는 중복 정보가 잘못되었습니다")
		}
		dependencyIDs[dep.Profile.ID] = true
		dependencies[dep.Path] = dep
	}
	if ids[m.SafeConfig.TranslationModel].Profile.Task != "translation" || ids[m.SafeConfig.STTModel].Profile.Task != "stt" {
		return errors.New("선택한 번역·음성인식 모델이 패키지에 없습니다")
	}
	cfg := defaultConfig()
	applyPortableConfig(&cfg, m.SafeConfig)
	if e := validateConfig(cfg); e != nil {
		return e
	}
	known := map[string]bool{strings.ToLower(portableManifestName): true}
	dirCases := map[string]string{}
	counts := map[string]int{}
	dependencyCounts := map[string]int{}
	total := int64(0)
	knowledge, exe := false, false
	for _, f := range m.Files {
		if !portableName(f.Path) || f.Bytes < 0 || f.Bytes > portableMaxFile || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(f.SHA256) {
			return errors.New("패키지 파일 메타데이터 오류")
		}
		lower := strings.ToLower(f.Path)
		parts := strings.Split(f.Path, "/")
		for i := 1; i <= len(parts); i++ {
			prefix := strings.Join(parts[:i], "/")
			folded := strings.ToLower(prefix)
			if old, ok := dirCases[folded]; ok && old != prefix {
				return errors.New("대소문자 폴더 경로 충돌")
			}
			dirCases[folded] = prefix
		}
		if known[lower] {
			return errors.New("대소문자 파일 충돌")
		}
		known[lower] = true
		if _, ok := entries[f.Path]; !ok || sizes[f.Path] != f.Bytes {
			return errors.New("패키지 파일 크기 또는 목록 불일치")
		}
		if f.Bytes > portableMaxTotal-total {
			return errors.New("패키지 전체 크기 초과")
		}
		total += f.Bytes
		if a, ok := ids[f.AssetID]; ok {
			if !strings.HasPrefix(f.Path, a.Root+"/") {
				return errors.New("모델 파일 경로 불일치")
			}
			counts[f.AssetID]++
			if !artifactBundle(a.Profile) && (strings.Count(strings.TrimPrefix(f.Path, a.Root+"/"), "/") != 0 || f.Bytes != a.Profile.Bytes || f.SHA256 != a.Profile.SHA256) {
				return errors.New("모델 검증 정보 불일치")
			}
		} else if dep, ok := dependencies[f.Path]; ok && f.AssetID == "" {
			if f.Bytes != dep.Profile.Bytes || f.SHA256 != dep.Profile.SHA256 {
				return errors.New("시스템 설치 파일의 고정 해시 또는 크기가 다릅니다")
			}
			dependencyCounts[dep.Profile.ID]++
		} else if f.AssetID == "" && f.Path == "knowledge.json" && f.Bytes <= portableMaxManifest {
			knowledge = true
		} else if f.AssetID == "" && f.Path == "application/MCastTalk.exe" {
			exe = true
		} else {
			return errors.New("허용되지 않은 패키지 파일")
		}
	}
	for n := range known {
		parts := strings.Split(n, "/")
		for i := 1; i < len(parts); i++ {
			if known[strings.Join(parts[:i], "/")] {
				return errors.New("파일과 폴더 경로 충돌")
			}
		}
	}
	for id, a := range ids {
		if counts[id] == 0 || !artifactBundle(a.Profile) && counts[id] != 1 {
			return errors.New("모델 파일이 없거나 중복됩니다")
		}
	}
	for id := range dependencyIDs {
		if dependencyCounts[id] != 1 {
			return errors.New("시스템 설치 파일이 없거나 중복됩니다")
		}
	}
	if knowledge != m.KnowledgeIncluded || exe != m.ExecutableIncluded {
		return errors.New("선택 정보 불일치")
	}
	return nil
}
func applyPortableConfig(c *Config, s portableConfig) {
	c.PublicBind = "127.0.0.1:8787"
	c.PublicURL = ""
	c.TLSCert = ""
	c.TLSKey = ""
	c.Access = "qr"
	c.Backend = "cpu"
	c.AutoResume = false
	c.Online = OnlineConfig{}
	if len(c.SpeakerPIN) < 6 {
		c.SpeakerPIN = newID()[:12]
	}
	if len(c.ListenerPIN) < 6 {
		c.ListenerPIN = newID()[:12]
	}
	c.SourceLanguage = s.SourceLanguage
	c.TargetLanguages = append([]string(nil), s.TargetLanguages...)
	c.TranslationModel = s.TranslationModel
	c.STTModel = s.STTModel
	c.MaxListeners = s.MaxListeners
}
func (p *PortableManager) diagnostic(ctx context.Context, m portableManifest) (PortableInspection, error) {
	d := Diagnose(ctx, p.store.dir)
	r := PortableInspection{Manifest: m, Diagnostic: d, Warnings: portableWarnings(m)}
	for _, f := range m.Files {
		r.RequiredDiskBytes += f.Bytes
	}
	r.RequiredDiskBytes += portableDiskReserve
	if p.targetOS != m.TargetOS || p.targetArch != m.TargetArch {
		return r, errors.New("이 PC는 Windows x64 패키지와 호환되지 않습니다")
	}
	free := int64(d.FreeDiskGB * (1 << 30))
	if p.diskAvailable != nil {
		free = p.diskAvailable()
	} else if !d.Measured {
		return r, errors.New("대상 PC의 여유 디스크를 측정할 수 없습니다")
	}
	if free < r.RequiredDiskBytes {
		return r, fmt.Errorf("디스크 공간이 부족합니다: 최소 %d 바이트 필요", r.RequiredDiskBytes)
	}
	r.Compatible = true
	return r, ctx.Err()
}
func copyPortable(ctx context.Context, dst io.Writer, src io.Reader, f portableFile) error {
	h := sha256.New()
	n, e := io.Copy(io.MultiWriter(dst, h), io.LimitReader(engineContextReader{ctx, src}, f.Bytes+1))
	if e != nil {
		return e
	}
	if n != f.Bytes || hex.EncodeToString(h.Sum(nil)) != f.SHA256 {
		return fmt.Errorf("파일 손상 또는 해시 불일치: %s", f.Path)
	}
	return ctx.Err()
}
func (p *PortableManager) Inspect(ctx context.Context, path string) (PortableInspection, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	s, e := openPortable(path)
	if e != nil {
		return PortableInspection{}, e
	}
	defer s.close()
	if e = p.verifyPortableBundlePins(ctx, s); e != nil {
		return PortableInspection{}, e
	}
	report, e := p.diagnostic(ctx, s.manifest)
	report.Fingerprint = s.fingerprint
	if e != nil {
		return report, e
	}
	done, total := int64(0), report.RequiredDiskBytes-portableDiskReserve
	for _, f := range s.manifest.Files {
		p.note("파일 무결성 검사", f.Path, done, total)
		r, e := s.entries[f.Path]()
		if e != nil {
			return report, e
		}
		e = copyPortable(ctx, io.Discard, r, f)
		ce := r.Close()
		if e != nil {
			return report, e
		}
		if ce != nil {
			return report, ce
		}
		done += f.Bytes
	}
	p.note("파일 무결성 확인 완료", "", done, total)
	return report, nil
}
func (p *PortableManager) Export(ctx context.Context, destination string, opts PortableExportOptions) (PortableReport, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	report := PortableReport{RequiresWarmup: true}
	p.note("원본 파일 확인", "", 0, 0)
	if opts.Format != "zip" && opts.Format != "folder" {
		return report, errors.New("ZIP 또는 폴더를 선택하세요")
	}
	if e := portableLocalPath(filepath.Dir(destination), true); e != nil {
		return report, e
	}
	if _, e := os.Lstat(destination); !os.IsNotExist(e) {
		return report, errors.New("기존 경로를 덮어쓰지 않습니다. 새 경로를 선택하세요")
	}
	if engineManagedPath(p.store.dir, "", filepath.Dir(destination)) || filepath.Clean(filepath.Dir(destination)) == filepath.Clean(p.store.dir) {
		return report, errors.New("서비스 자료 폴더 밖으로 내보내세요")
	}
	c := p.store.config()
	m := portableManifest{Schema: 1, AppVersion: Version, TargetOS: "windows", TargetArch: "amd64", CreatedAt: time.Now().UTC(), SafeConfig: portableConfig{c.SourceLanguage, c.TargetLanguages, c.TranslationModel, c.STTModel, c.MaxListeners, c.Backend}, KnowledgeIncluded: opts.IncludeKnowledge, ExecutableIncluded: opts.IncludeEXE}
	installed := p.store.assets()
	profiles := map[string]Artifact{}
	for _, a := range p.assets.Registry() {
		profiles[a.ID] = a
	}
	ids := opts.AssetIDs
	if len(ids) == 0 {
		ids = []string{c.TranslationModel, c.STTModel, "runtime-llama-cpu", "runtime-whisper-cpu"}
		ids = append(ids, bundledTTSAssetIDs(append([]string{c.SourceLanguage}, c.TargetLanguages...))...)
		for id := range installed {
			if strings.HasPrefix(id, "runtime-") {
				ids = append(ids, id)
			}
		}
	}
	files := map[string]string{}
	memory := map[string][]byte{}
	seen := map[string]bool{}
	add := func(name, id, path string) error {
		if !portableName(name) {
			return errors.New("이식할 수 없는 파일 이름")
		}
		if e := portableLocalPath(path, false); e != nil {
			return e
		}
		f, e := os.Open(path)
		if e != nil {
			return e
		}
		h := sha256.New()
		n, e := io.Copy(h, io.LimitReader(engineContextReader{ctx, f}, portableMaxFile+1))
		ce := f.Close()
		if e != nil {
			return e
		}
		if ce != nil {
			return ce
		}
		if n < 0 || n > portableMaxFile {
			return errors.New("파일 크기 범위 초과")
		}
		m.Files = append(m.Files, portableFile{name, id, n, hex.EncodeToString(h.Sum(nil))})
		files[name] = path
		return nil
	}
	for _, id := range ids {
		if seen[id] {
			continue
		}
		seen[id] = true
		art, ok := profiles[id]
		if !ok {
			return report, fmt.Errorf("등록되지 않은 모델: %s", id)
		}
		if e := portableProfile(art); e != nil {
			return report, e
		}
		asset, ok := installed[id]
		if !ok {
			return report, fmt.Errorf("오프라인 운영에 필요한 파일을 먼저 준비하세요: %s", id)
		}
		if asset.SHA256 != art.SHA256 || asset.Bytes != art.Bytes {
			return report, errors.New("설치 검증 정보 불일치")
		}
		folder := "models"
		if art.Task == "runtime" {
			folder = "runtimes"
		}
		if !engineManagedPath(p.store.dir, folder, asset.Path) {
			return report, errors.New("관리 폴더의 모델·엔진만 내보낼 수 있습니다")
		}
		root := folder + "/" + id
		m.Assets = append(m.Assets, portableAsset{art, root})
		if artifactBundle(art) {
			if e := portableLocalPath(asset.Path, true); e != nil {
				return report, e
			}
			if e := verifyPinnedArtifactBundle(ctx, asset.Path, p.trustedBundleProfile(art)); e != nil {
				return report, e
			}
			if e := filepath.Walk(asset.Path, func(path string, info os.FileInfo, err error) error {
				if err != nil {
					return err
				}
				if info.IsDir() {
					return portableLocalPath(path, true)
				}
				rel, _ := filepath.Rel(asset.Path, path)
				return add(root+"/"+filepath.ToSlash(rel), id, path)
			}); e != nil {
				return report, e
			}
		} else {
			if e := portableModelHeader(asset.Path, art); e != nil {
				return report, e
			}
			// The streaming copy below checks the registered hash while writing.
			// A multi-GB model therefore needs one full source pass, not three.
			name := root + "/" + filepath.Base(asset.Path)
			m.Files = append(m.Files, portableFile{name, id, art.Bytes, art.SHA256})
			files[name] = asset.Path
		}
	}
	for _, dep := range SystemDependencies() {
		path, err := SystemDependencyInstallerPath(p.store.dir, dep.ID)
		if err != nil {
			return report, err
		}
		if _, err := os.Lstat(path); os.IsNotExist(err) {
			continue
		} else if err != nil {
			return report, err
		}
		if e := portableLocalPath(path, false); e != nil {
			return report, e
		}
		if e := verifyModel(ctx, path, dep.SHA256, dep.Bytes, nil); e != nil {
			return report, fmt.Errorf("Microsoft 설치 캐시 무결성 확인 실패: %w", e)
		}
		if e := portableDependencyPE(path, dep); e != nil {
			return report, e
		}
		name := portableDependencyPath(dep)
		m.SystemDependencies = append(m.SystemDependencies, portableSystemDependency{Profile: dep, Path: name})
		m.Files = append(m.Files, portableFile{name, "", dep.Bytes, dep.SHA256})
		files[name] = path
	}
	if opts.IncludeKnowledge {
		k := portableKnowledge{}
		if e := p.store.get("glossary", "terms", &k.Terms); e != nil && !os.IsNotExist(e) {
			return report, e
		}
		raw, e := p.store.all("scripts")
		if e != nil {
			return report, e
		}
		for _, b := range raw {
			var s portableScript
			if e := json.Unmarshal(b, &s); e != nil {
				return report, e
			}
			k.Scripts = append(k.Scripts, s)
		}
		raw, e = p.store.all("lessons")
		if e != nil {
			return report, e
		}
		for _, b := range raw {
			var l Lesson
			if e := json.Unmarshal(b, &l); e != nil {
				return report, e
			}
			if l.Status == "approved" {
				k.Lessons = append(k.Lessons, l)
			}
		}
		b, e := json.Marshal(k)
		if e != nil {
			return report, e
		}
		if len(b) > portableMaxManifest {
			return report, errors.New("용어·자료가 너무 큽니다")
		}
		sum := sha256.Sum256(b)
		memory["knowledge.json"] = b
		m.Files = append(m.Files, portableFile{"knowledge.json", "", int64(len(b)), hex.EncodeToString(sum[:])})
	}
	if opts.IncludeEXE {
		if runtime.GOOS != "windows" || runtime.GOARCH != "amd64" {
			return report, errors.New("실행파일 포함은 Windows x64에서만 가능합니다")
		}
		exe, e := os.Executable()
		if e != nil {
			return report, e
		}
		if e := portablePE(exe); e != nil {
			return report, e
		}
		if e := add("application/MCastTalk.exe", "", exe); e != nil {
			return report, e
		}
	}
	sort.Slice(m.Files, func(i, j int) bool { return m.Files[i].Path < m.Files[j].Path })
	entries := map[string]func() (io.ReadCloser, error){portableManifestName: nil}
	sizes := map[string]int64{}
	for _, f := range m.Files {
		entries[f.Path] = nil
		sizes[f.Path] = f.Bytes
		report.Bytes += f.Bytes
	}
	if e := validatePortableManifest(m, entries, sizes); e != nil {
		return report, e
	}
	b, e := json.MarshalIndent(m, "", "  ")
	if e != nil {
		return report, e
	}
	if len(b) > portableMaxManifest {
		return report, errors.New("매니페스트가 너무 큽니다")
	}
	if free, err := portableFreeDisk(filepath.Dir(destination)); err != nil {
		return report, err
	} else if free < report.Bytes+int64(len(b))+portableDiskReserve {
		return report, errors.New("내보내기 공간이 부족합니다")
	}
	stage, e := os.MkdirTemp(filepath.Dir(destination), ".mcast-export-")
	if e != nil {
		return report, e
	}
	defer os.RemoveAll(stage)
	output := stage
	if opts.Format == "zip" {
		output = filepath.Join(stage, "environment.zip")
	}
	var z *zip.Writer
	var out *os.File
	if opts.Format == "zip" {
		out, e = os.OpenFile(output, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if e != nil {
			return report, e
		}
		defer out.Close()
		z = zip.NewWriter(out)
	}
	write := func(name string, r io.Reader, meta *portableFile) error {
		var w io.Writer
		var file *os.File
		if z != nil {
			h := &zip.FileHeader{Name: name, Method: zip.Store}
			h.SetMode(0600)
			w, e = z.CreateHeader(h)
		} else {
			path := filepath.Join(stage, filepath.FromSlash(name))
			e = os.MkdirAll(filepath.Dir(path), 0700)
			if e == nil {
				file, e = os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
				w = file
			}
		}
		if e != nil {
			return e
		}
		if file != nil {
			defer file.Close()
		}
		if meta != nil {
			e = copyPortable(ctx, w, r, *meta)
		} else {
			_, e = io.Copy(w, engineContextReader{ctx, r})
		}
		if e != nil {
			return e
		}
		if file != nil {
			return file.Sync()
		}
		return nil
	}
	if e := write(portableManifestName, strings.NewReader(string(b)), nil); e != nil {
		return report, e
	}
	done := int64(0)
	for _, f := range m.Files {
		p.note("패키지 복사·해시 확인", f.Path, done, report.Bytes)
		if data, ok := memory[f.Path]; ok {
			e = write(f.Path, strings.NewReader(string(data)), &f)
		} else {
			var r *os.File
			r, e = os.Open(files[f.Path])
			if e == nil {
				e = write(f.Path, r, &f)
				ce := r.Close()
				if e == nil {
					e = ce
				}
			}
		}
		if e != nil {
			return report, e
		}
		done += f.Bytes
	}
	if z != nil {
		if e = z.Close(); e != nil {
			return report, e
		}
		if e = out.Sync(); e != nil {
			return report, e
		}
		if e = out.Close(); e != nil {
			return report, e
		}
	}
	if e = ctx.Err(); e != nil {
		return report, e
	}
	if _, e = os.Lstat(destination); !os.IsNotExist(e) {
		return report, errors.New("목적 경로가 이미 존재합니다")
	}
	if e = os.Rename(output, destination); e != nil {
		return report, e
	}
	report.OfflineComplete = portableComplete(m)
	report.Warnings = portableWarnings(m)
	p.note("내보내기 완료", "", report.Bytes, report.Bytes)
	return report, nil
}
func (p *PortableManager) Import(ctx context.Context, path string) (PortableReport, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	report := PortableReport{RequiresWarmup: true}
	p.note("호환성·공간 검사", "", 0, 0)
	if e := p.importStoreIdle(); e != nil {
		return report, e
	}
	s, e := openPortable(path)
	if e != nil {
		return report, e
	}
	defer s.close()
	m := s.manifest
	if p.expectedManifestSHA != "" && p.expectedManifestSHA != s.fingerprint {
		return report, errors.New("검사 후 패키지가 변경되었습니다. 다시 검사하세요")
	}
	if e = p.verifyPortableBundlePins(ctx, s); e != nil {
		return report, e
	}
	if _, e = p.diagnostic(ctx, m); e != nil {
		return report, e
	}
	if e = portableLocalPath(p.store.dir, true); e != nil {
		return report, e
	}
	stage, e := os.MkdirTemp(p.store.dir, ".portable-stage-")
	if e != nil {
		return report, e
	}
	defer os.RemoveAll(stage)
	total := int64(0)
	for _, f := range m.Files {
		total += f.Bytes
	}
	for _, f := range m.Files {
		p.note("가져오기·해시 확인", f.Path, report.Bytes, total)
		dest := filepath.Join(stage, filepath.FromSlash(f.Path))
		if e = os.MkdirAll(filepath.Dir(dest), 0700); e != nil {
			return report, e
		}
		r, e := s.entries[f.Path]()
		if e != nil {
			return report, e
		}
		w, e := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if e != nil {
			r.Close()
			return report, e
		}
		e = copyPortable(ctx, w, r, f)
		ce := r.Close()
		if e == nil {
			e = ce
		}
		if e == nil {
			e = w.Sync()
		}
		ce = w.Close()
		if e == nil {
			e = ce
		}
		if e != nil {
			return report, e
		}
		lower := strings.ToLower(f.Path)
		if strings.HasSuffix(lower, ".exe") || strings.HasSuffix(lower, ".dll") {
			if dep, ok := portableDependencyForFile(m, f); ok {
				e = portableDependencyPE(dest, dep)
			} else {
				e = portablePE(dest)
			}
			if e != nil {
				return report, e
			}
		}
		report.Bytes += f.Bytes
	}
	var knowledge portableKnowledge
	if m.KnowledgeIncluded {
		b, e := os.ReadFile(filepath.Join(stage, "knowledge.json"))
		if e != nil {
			return report, e
		}
		if e = portableJSON(b, &knowledge); e != nil {
			return report, e
		}
		if e = validatePortableKnowledge(knowledge); e != nil {
			return report, e
		}
	}
	for _, a := range m.Assets {
		if artifactBundle(a.Profile) {
			if e = verifyPinnedArtifactBundle(ctx, filepath.Join(stage, filepath.FromSlash(a.Root)), p.trustedBundleProfile(a.Profile)); e != nil {
				return report, e
			}
		} else {
			root := filepath.Join(stage, filepath.FromSlash(a.Root))
			files, e := os.ReadDir(root)
			if e != nil {
				return report, e
			}
			if len(files) != 1 {
				return report, errors.New("모델 파일 목록 오류")
			}
			if e = portableModelHeader(filepath.Join(root, files[0].Name()), a.Profile); e != nil {
				return report, e
			}
		}
	}
	paths := []string{}
	type dependencyCacheChange struct{ path, backup string }
	cacheChanges := []dependencyCacheChange{}
	p.note("검증한 환경 적용", "", report.Bytes, total)
	activated := false
	defer func() {
		if !activated {
			for i := len(cacheChanges) - 1; i >= 0; i-- {
				change := cacheChanges[i]
				_ = os.Remove(change.path)
				if change.backup != "" {
					_ = os.Rename(change.backup, change.path)
				}
			}
			for _, path := range paths {
				_ = os.RemoveAll(path)
			}
		}
	}()
	for _, a := range m.Assets {
		folder := "models"
		if a.Profile.Task == "runtime" {
			folder = "runtimes"
		}
		parent := filepath.Join(p.store.dir, folder)
		if e = os.MkdirAll(parent, 0700); e != nil {
			return report, e
		}
		if e = portableLocalPath(parent, true); e != nil {
			return report, e
		}
		dest := filepath.Join(parent, "portable-"+newID())
		if e = os.Rename(filepath.Join(stage, filepath.FromSlash(a.Root)), dest); e != nil {
			return report, e
		}
		paths = append(paths, dest)
		installedPath := dest
		if !artifactBundle(a.Profile) {
			entries, _ := os.ReadDir(dest)
			installedPath = filepath.Join(dest, entries[0].Name())
		}
		report.Installed = append(report.Installed, InstalledAsset{ID: a.Profile.ID, Path: installedPath, SHA256: a.Profile.SHA256, Bytes: a.Profile.Bytes, InstalledAt: time.Now().UTC()})
	}
	if m.ExecutableIncluded {
		parent := filepath.Join(p.store.dir, "portable-applications")
		if e = os.MkdirAll(parent, 0700); e != nil {
			return report, e
		}
		if e = portableLocalPath(parent, true); e != nil {
			return report, e
		}
		dest := filepath.Join(parent, "portable-"+newID())
		if e = os.Rename(filepath.Join(stage, "application"), dest); e != nil {
			return report, e
		}
		paths = append(paths, dest)
		report.Warnings = append(report.Warnings, "포함 실행파일은 "+dest+"에 보관했습니다. 자동 실행하거나 현재 프로그램을 덮어쓰지 않았습니다.")
	}
	for _, dep := range m.SystemDependencies {
		path, err := SystemDependencyInstallerPath(p.store.dir, dep.Profile.ID)
		if err != nil {
			return report, err
		}
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			return report, e
		}
		if e = portableLocalPath(filepath.Dir(path), true); e != nil {
			return report, e
		}
		backup := ""
		if _, err = os.Lstat(path); err == nil {
			if e = portableLocalPath(path, false); e != nil {
				return report, e
			}
			if e = verifyModel(ctx, path, dep.Profile.SHA256, dep.Profile.Bytes, nil); e == nil {
				continue
			}
			if ctx.Err() != nil {
				return report, ctx.Err()
			}
			backup = filepath.Join(stage, "old-dependency-"+dep.Profile.ID)
			if e = os.Rename(path, backup); e != nil {
				return report, e
			}
		} else if !os.IsNotExist(err) {
			return report, err
		}
		cacheChanges = append(cacheChanges, dependencyCacheChange{path, backup})
		if e = os.Rename(filepath.Join(stage, filepath.FromSlash(dep.Path)), path); e != nil {
			return report, e
		}
	}
	cfg := p.store.config()
	applyPortableConfig(&cfg, m.SafeConfig)
	if e = validateConfig(cfg); e != nil {
		return report, e
	}
	configBytes, e := json.Marshal(cfg)
	if e != nil {
		return report, e
	}
	p.assets.mu.Lock()
	defer p.assets.mu.Unlock()
	oldRegistry := append([]Artifact(nil), p.assets.registry...)
	oldCustom := append([]Artifact(nil), p.assets.customRegistry...)
	registryPath := filepath.Join(p.store.dir, "registry.json")
	oldRegistryFile, readErr := os.ReadFile(registryPath)
	if readErr != nil && !os.IsNotExist(readErr) {
		return report, readErr
	}
	changed := false
	defer func() {
		if activated {
			return
		}
		p.assets.registry = oldRegistry
		p.assets.customRegistry = oldCustom
		if changed {
			if os.IsNotExist(readErr) {
				_ = os.Remove(registryPath)
			} else {
				_ = atomicFile(registryPath, oldRegistryFile)
			}
		}
	}()
	for _, a := range m.Assets {
		if !strings.HasPrefix(a.Profile.ID, "custom-") {
			continue
		}
		found := false
		for _, b := range p.assets.registry {
			if b.ID == a.Profile.ID {
				if !reflect.DeepEqual(a.Profile, b) {
					return report, errors.New("대상 PC의 동일 모델 프로필과 충돌합니다")
				}
				found = true
			}
		}
		if !found {
			p.assets.customRegistry = append(p.assets.customRegistry, a.Profile)
			p.assets.registry = append(p.assets.registry, a.Profile)
			changed = true
		}
	}
	if changed {
		if e = p.assets.saveRegistry(); e != nil {
			return report, e
		}
	}
	if e = ctx.Err(); e != nil {
		return report, e
	}
	if p.beforeCommit != nil {
		if e = p.beforeCommit(); e != nil {
			return report, e
		}
	}
	e = p.store.db.Update(func(tx *bolt.Tx) error {
		if e := ctx.Err(); e != nil {
			return e
		}
		for _, a := range report.Installed {
			b, e := json.Marshal(a)
			if e != nil {
				return e
			}
			if e = tx.Bucket([]byte("assets")).Put([]byte(a.ID), b); e != nil {
				return e
			}
		}
		settings := tx.Bucket([]byte("settings"))
		if e := settings.Put([]byte("config"), configBytes); e != nil {
			return e
		}
		if e := settings.Delete([]byte("online-secret")); e != nil {
			return e
		}
		if m.KnowledgeIncluded {
			return mergePortableKnowledge(tx, knowledge)
		}
		return nil
	})
	if e != nil {
		return report, e
	}
	activated = true
	report.OfflineComplete = portableComplete(m)
	report.Warnings = append(report.Warnings, portableWarnings(m)...)
	p.note("환경 적용 완료", "", report.Bytes, total)
	return report, nil
}
func (p *PortableManager) importStoreIdle() error {
	p.assets.muCancel.Lock()
	active := len(p.assets.cancelMap) != 0
	p.assets.muCancel.Unlock()
	if active {
		return errors.New("모델 파일 작업을 먼저 완료하세요")
	}
	for _, d := range p.assets.Progress() {
		if d.State == "downloading" || d.State == "importing" {
			return errors.New("모델 파일 작업을 먼저 완료하세요")
		}
	}
	for _, bucket := range []string{"jobs", "sessions", "rooms"} {
		rows, err := p.store.all(bucket)
		if err != nil {
			return err
		}
		for _, raw := range rows {
			if bucket == "jobs" {
				var j Job
				if err = json.Unmarshal(raw, &j); err != nil {
					return err
				}
				if j.State == "queued" || j.State == "running" || j.State == "collecting" {
					return errors.New("재개할 작업을 먼저 완료하세요")
				}
			}
			if bucket == "rooms" {
				var room Room
				if err = json.Unmarshal(raw, &room); err != nil {
					return err
				}
				if room.State == "active" {
					return errors.New("강의실을 먼저 닫으세요")
				}
			}
			if bucket == "sessions" {
				var s Session
				if err = json.Unmarshal(raw, &s); err != nil {
					return err
				}
				if s.State == "active" {
					roomID := s.ID
					if s.Kind == "private" {
						var ch Channel
						if p.store.get("channels", s.ID, &ch) != nil {
							return errors.New("개인 세션의 연결 상태를 복구하고 종료하세요")
						}
						roomID = ch.RoomID
					}
					var room Room
					closed := (s.Kind == "private" || s.Kind == "classroom") && p.store.get("rooms", roomID, &room) == nil && room.State == "closed"
					if !closed {
						return errors.New("세션을 먼저 종료하세요")
					}
				}
			}
		}
	}
	return nil
}
func validatePortableKnowledge(k portableKnowledge) error {
	if len(k.Terms) > 10000 || len(k.Scripts) > 10000 || len(k.Lessons) > 10000 {
		return errors.New("자료 건수 초과")
	}
	for _, t := range k.Terms {
		if len(t.Source) > 512 || len(t.Target) > 512 || !languagePattern.MatchString(t.Language) {
			return errors.New("용어 형식 오류")
		}
	}
	for _, s := range k.Scripts {
		if len(s.Title) > 512 || len(s.Text) > 1<<20 {
			return errors.New("대본 크기 초과")
		}
	}
	for _, l := range k.Lessons {
		if l.Status != "approved" || !languagePattern.MatchString(l.Language) || len(l.Source) > 8192 || len(l.Before) > 1<<20 || len(l.Proposed) > 1<<20 {
			return errors.New("승인된 학습 자료만 가져올 수 있습니다")
		}
	}
	return nil
}
func mergePortableKnowledge(tx *bolt.Tx, k portableKnowledge) error {
	terms := []GlossaryTerm{}
	raw := tx.Bucket([]byte("glossary")).Get([]byte("terms"))
	if raw != nil {
		if e := json.Unmarshal(raw, &terms); e != nil {
			return e
		}
	}
	seen := map[GlossaryTerm]bool{}
	for _, t := range terms {
		seen[t] = true
	}
	for _, t := range k.Terms {
		if !seen[t] {
			terms = append(terms, t)
			seen[t] = true
		}
	}
	if len(terms) > 10000 {
		return errors.New("대상 용어집 건수 초과")
	}
	b, e := json.Marshal(terms)
	if e != nil {
		return e
	}
	if e = tx.Bucket([]byte("glossary")).Put([]byte("terms"), b); e != nil {
		return e
	}
	for _, s := range k.Scripts {
		s.ID = newID()
		b, e := json.Marshal(s)
		if e != nil {
			return e
		}
		if e = tx.Bucket([]byte("scripts")).Put([]byte(s.ID), b); e != nil {
			return e
		}
	}
	for _, l := range k.Lessons {
		l.ID = newID()
		b, e := json.Marshal(l)
		if e != nil {
			return e
		}
		if e = tx.Bucket([]byte("lessons")).Put([]byte(l.ID), b); e != nil {
			return e
		}
		if e = tx.Bucket([]byte("lesson-index")).Put(lessonKey(l.Source, l.Language), []byte(l.ID)); e != nil {
			return e
		}
	}
	return nil
}
