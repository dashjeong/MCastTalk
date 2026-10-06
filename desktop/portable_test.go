package main

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	bolt "go.etcd.io/bbolt"
)

// These tiny GGUF/GGML/PE fixtures exercise transfer integrity and rollback.
// They are not runnable AI models, Windows acceptance, or capacity evidence.
type portableTestFixture struct {
	store     *Store
	assets    *AssetManager
	manager   *PortableManager
	installed map[string]InstalledAsset
	ids       []string
}

func TestPortableWindowsPathAliases(t *testing.T) {
	for _, name := range []string{"runtimes/x/COM¹.dll", "models/x/CON .a.b", "runtimes/x/conout$.exe", "runtimes/x/LPT³.txt"} {
		if portableName(name) {
			t.Errorf("Windows device alias accepted: %s", name)
		}
	}
}

func portableTestHash(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func portableTestTemp(t *testing.T) string {
	t.Helper()
	path, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return path
}

func portableTestPE() []byte {
	b := make([]byte, 512)
	copy(b, "MZ")
	binary.LittleEndian.PutUint32(b[0x3c:], 0x80)
	copy(b[0x80:], "PE\x00\x00")
	binary.LittleEndian.PutUint16(b[0x84:], 0x8664) // AMD64; zero sections.
	return b
}

func portableTestOpen(t *testing.T, label string) *portableTestFixture {
	t.Helper()
	dir := filepath.Join(portableTestTemp(t), "한글 환경 "+label)
	s, err := openStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.db.Close() })
	am := NewAssetManager(dir, func(a InstalledAsset) error { return s.put("assets", a.ID, a) })
	f := &portableTestFixture{store: s, assets: am}
	commit := strings.Repeat("a", 40)
	custom := []Artifact{}
	for _, task := range []string{"translation", "stt"} {
		repo := "fixture/" + label + "-" + task
		name, format, prompt := "model.gguf", "gguf", "chat"
		data := make([]byte, 32)
		copy(data, "GGUF")
		binary.LittleEndian.PutUint32(data[4:], 3)
		if task == "stt" {
			name, format, prompt = "model.bin", "ggml", ""
			copy(data, "lmgg")
		}
		identity := sha256.Sum256([]byte(repo + "/" + commit + "/" + name))
		id := "custom-" + hex.EncodeToString(identity[:10])
		family := "fixture"
		if task == "stt" {
			family = "whisper"
		}
		profile := Artifact{ID: id, Name: "Fixture " + task, Task: task, Family: family, Prompt: prompt, Format: format, URL: "https://huggingface.co/" + repo + "/resolve/" + commit + "/" + name, SHA256: portableTestHash(data), Bytes: int64(len(data)), RAMGB: 1, Source: repo + "@" + commit, Backends: []string{"cpu"}, Experimental: true}
		custom = append(custom, profile)
		path := filepath.Join(dir, "models", id+filepath.Ext(name))
		if err := atomicFile(path, data); err != nil {
			t.Fatal(err)
		}
		if err := s.put("assets", id, InstalledAsset{ID: id, Path: path, SHA256: profile.SHA256, Bytes: profile.Bytes, InstalledAt: time.Now().UTC()}); err != nil {
			t.Fatal(err)
		}
		f.ids = append(f.ids, id)
	}
	registry, _ := json.Marshal(custom)
	if err := atomicFile(filepath.Join(dir, "registry.json"), registry); err != nil {
		t.Fatal(err)
	}
	am = NewAssetManager(dir, func(a InstalledAsset) error { return s.put("assets", a.ID, a) })
	f.assets = am
	for _, id := range []string{"runtime-llama-cpu", "runtime-whisper-cpu"} {
		profile, ok := am.getArtifact(id)
		if !ok {
			t.Fatalf("required built-in runtime missing: %s", id)
		}
		exe := "llama-server.exe"
		if id == "runtime-whisper-cpu" {
			exe = "whisper-server.exe"
		}
		root := filepath.Join(dir, "runtimes", id+"-fixture")
		data := portableTestPE()
		files := []map[string]any{}
		for _, name := range []string{"Release/" + exe, "Release/fixture.dll"} {
			if err := atomicFile(filepath.Join(root, filepath.FromSlash(name)), data); err != nil {
				t.Fatal(err)
			}
			files = append(files, map[string]any{"path": name, "sha256": portableTestHash(data), "bytes": len(data)})
		}
		manifest, _ := json.Marshal(map[string]any{"files": files})
		if err := atomicFile(filepath.Join(root, "installed-manifest.json"), manifest); err != nil {
			t.Fatal(err)
		}
		// Archive hash/size identifies the built-in profile, not the extracted tree.
		if err := s.put("assets", id, InstalledAsset{ID: id, Path: root, SHA256: profile.SHA256, Bytes: profile.Bytes, InstalledAt: time.Now().UTC()}); err != nil {
			t.Fatal(err)
		}
		f.ids = append(f.ids, id)
	}
	portableTestAddTTSBundles(t, f)
	cfg := defaultConfig()
	cfg.TranslationModel, cfg.STTModel = f.ids[0], f.ids[1]
	pinSuffix := portableTestHash([]byte(label))[:12]
	cfg.SpeakerPIN, cfg.ListenerPIN = "target-speaker-"+pinSuffix, "target-listener-"+pinSuffix
	if err := validateConfig(cfg); err != nil {
		t.Fatalf("invalid portable fixture configuration: %v", err)
	}
	if err := s.saveConfig(cfg); err != nil {
		t.Fatal(err)
	}
	f.installed = s.assets()
	f.manager = NewPortableManager(s, am)
	f.manager.targetOS, f.manager.targetArch = "windows", "amd64"
	f.manager.diskAvailable = func() int64 { return 1 << 40 }
	// These synthetic files are deliberately not claimed to be official models.
	// Pin their complete trees explicitly at the test boundary; production has
	// no override and must match the immutable catalogue archive-derived pins.
	f.manager.bundleTreePins = map[string]string{}
	for _, id := range f.ids {
		profile, _ := f.assets.getArtifact(id)
		if !artifactBundle(profile) {
			continue
		}
		m, err := readBundleManifest(context.Background(), f.installed[id].Path)
		if err != nil {
			t.Fatal(err)
		}
		pin, err := canonicalBundleTreeSHA(m)
		if err != nil {
			t.Fatal(err)
		}
		f.manager.bundleTreePins[id] = pin
	}
	return f
}

func portableTestSeedSource(t *testing.T, f *portableTestFixture) []string {
	t.Helper()
	cfg := f.store.config()
	cfg.PublicBind, cfg.PublicURL = "0.0.0.0:8787", "https://source-only-secret.invalid"
	cfg.TLSCert, cfg.TLSKey = "source-only-cert.pem", "source-only-private-key.pem"
	cfg.SpeakerPIN, cfg.ListenerPIN = "SOURCE-PIN-SPEAKER", "SOURCE-PIN-LISTENER"
	cfg.Backend, cfg.AutoResume = "cuda", true
	cfg.Online = OnlineConfig{Endpoint: "https://source-only-provider.invalid/v1", Model: "SOURCE-ONLINE-MODEL", APIKey: "SOURCE-API-KEY-PRIVATE", Consent: true}
	if err := f.store.saveConfig(cfg); err != nil {
		t.Fatal(err)
	}
	terms := []GlossaryTerm{{Source: "안전 용어", Target: "approved term", Language: "en"}}
	if err := f.store.put("glossary", "terms", terms); err != nil {
		t.Fatal(err)
	}
	scriptID := strings.Repeat("c", 32)
	if err := f.store.put("scripts", scriptID, map[string]string{"id": scriptID, "title": "Reusable script", "text": "APPROVED-REUSABLE-CONTEXT"}); err != nil {
		t.Fatal(err)
	}
	for i, status := range []string{"approved", "pending", "held"} {
		id := strings.Repeat(string(rune('d'+i)), 32)
		text := "EXCLUDED-" + strings.ToUpper(status) + "-LESSON"
		if status == "approved" {
			text = "approved lesson result"
		}
		if err := f.store.put("lessons", id, Lesson{ID: id, Source: "lesson " + status, Language: "en", Proposed: text, Status: status}); err != nil {
			t.Fatal(err)
		}
	}
	privateID := strings.Repeat("b", 32)
	if err := f.store.put("sessions", privateID, Session{ID: privateID, Kind: "private", State: "stopped", SourceLanguage: "ko", Token: "SOURCE-PRIVATE-JOIN-TOKEN", Title: "SOURCE-PRIVATE-CONVERSATION"}); err != nil {
		t.Fatal(err)
	}
	if err := f.store.writeRecording(privateID, "private.wav", []byte("SOURCE-PRIVATE-RECORDING")); err != nil {
		t.Fatal(err)
	}
	if err := f.store.put("members", strings.Repeat("a", 32), map[string]string{"name": "SOURCE-PRIVATE-MEMBER", "token": "SOURCE-MEMBER-TOKEN"}); err != nil {
		t.Fatal(err)
	}
	return []string{cfg.PublicURL, cfg.TLSCert, cfg.TLSKey, cfg.SpeakerPIN, cfg.ListenerPIN, cfg.Online.Endpoint, cfg.Online.Model, cfg.Online.APIKey, "SOURCE-PRIVATE-JOIN-TOKEN", "SOURCE-PRIVATE-CONVERSATION", "SOURCE-PRIVATE-RECORDING", "SOURCE-PRIVATE-MEMBER", "SOURCE-MEMBER-TOKEN", "EXCLUDED-PENDING-LESSON", "EXCLUDED-HELD-LESSON"}
}

type portableTestEntry struct {
	name   string
	data   []byte
	mode   os.FileMode
	method uint16
}

func portableTestReadZIP(t *testing.T, path string) []portableTestEntry {
	t.Helper()
	r, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	entries := []portableTestEntry{}
	for _, file := range r.File {
		in, err := file.Open()
		if err != nil {
			t.Fatal(err)
		}
		b, err := io.ReadAll(io.LimitReader(in, 16<<20))
		in.Close()
		if err != nil {
			t.Fatal(err)
		}
		entries = append(entries, portableTestEntry{file.Name, b, file.Mode(), file.Method})
	}
	return entries
}

func portableTestWriteZIP(t *testing.T, path string, entries []portableTestEntry) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	z := zip.NewWriter(f)
	for _, entry := range entries {
		h := &zip.FileHeader{Name: entry.name, Method: entry.method}
		h.SetMode(entry.mode)
		w, err := z.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = w.Write(entry.data); err != nil {
			t.Fatal(err)
		}
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func portableTestManifest(t *testing.T, entries []portableTestEntry, change func(map[string]any)) {
	t.Helper()
	for i := range entries {
		if entries[i].name == "portable-manifest.json" {
			var m map[string]any
			if err := json.Unmarshal(entries[i].data, &m); err != nil {
				t.Fatal(err)
			}
			change(m)
			b, err := json.Marshal(m)
			if err != nil {
				t.Fatal(err)
			}
			entries[i].data = b
			return
		}
	}
	t.Fatal("export omitted portable manifest")
}

func portableTestWalkMaps(v any, fn func(map[string]any)) {
	switch x := v.(type) {
	case map[string]any:
		fn(x)
		for _, child := range x {
			portableTestWalkMaps(child, fn)
		}
	case []any:
		for _, child := range x {
			portableTestWalkMaps(child, fn)
		}
	}
}

func portableTestArchive(t *testing.T, source *portableTestFixture, knowledge bool) string {
	t.Helper()
	path := filepath.Join(portableTestTemp(t), "이동 환경.zip")
	_, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: "zip", AssetIDs: source.ids, IncludeKnowledge: knowledge})
	if err != nil {
		t.Fatal(err)
	}
	return path
}

func TestPortableOfflineRoundtripAndSecretExclusion(t *testing.T) {
	for _, format := range []string{"zip", "folder"} {
		t.Run(format, func(t *testing.T) {
			source := portableTestOpen(t, "source")
			forbidden := portableTestSeedSource(t, source)
			dest := filepath.Join(portableTestTemp(t), "오프라인 이동 "+format)
			if _, err := source.manager.Export(context.Background(), dest, PortableExportOptions{Format: format, AssetIDs: source.ids, IncludeKnowledge: true}); err != nil {
				t.Fatal(err)
			}
			var payload bytes.Buffer
			if format == "zip" {
				for _, entry := range portableTestReadZIP(t, dest) {
					payload.WriteString(entry.name)
					payload.Write(entry.data)
					if strings.HasPrefix(entry.name, "models/") && entry.method != zip.Store {
						t.Fatal("model export was compressed instead of streamed Store")
					}
				}
			} else {
				if err := filepath.Walk(dest, func(path string, info os.FileInfo, err error) error {
					if err != nil || info.IsDir() {
						return err
					}
					b, err := os.ReadFile(path)
					payload.WriteString(path)
					payload.Write(b)
					return err
				}); err != nil {
					t.Fatal(err)
				}
			}
			for _, marker := range append(forbidden, "private-data.key", "online-secret", "recordings/") {
				if bytes.Contains(payload.Bytes(), []byte(marker)) {
					t.Fatalf("excluded source information exported: %q", marker)
				}
			}
			target := portableTestOpen(t, "target")
			before := target.store.config()
			before.Online = OnlineConfig{Endpoint: "https://target-provider.invalid", APIKey: "TARGET-OLD-KEY", Consent: true}
			if err := target.store.saveConfig(before); err != nil {
				t.Fatal(err)
			}
			// A colliding reusable script must survive and imported data get new IDs.
			collision := strings.Repeat("c", 32)
			if err := target.store.put("scripts", collision, map[string]string{"id": collision, "title": "Target existing", "text": "target preserved"}); err != nil {
				t.Fatal(err)
			}
			oldTransport, oldDefaultTransport := http.DefaultClient.Transport, http.DefaultTransport
			blockedTransport := &mockTransport{roundTripFunc: func(*http.Request) (*http.Response, error) {
				t.Error("offline transfer attempted HTTP")
				return nil, errors.New("offline fixture forbids HTTP")
			}}
			http.DefaultClient.Transport, http.DefaultTransport = blockedTransport, blockedTransport
			defer func() { http.DefaultClient.Transport, http.DefaultTransport = oldTransport, oldDefaultTransport }()
			inspection, err := target.manager.Inspect(context.Background(), dest)
			if err != nil || !inspection.Compatible || inspection.RequiredDiskBytes <= 0 {
				t.Fatalf("inspection failed: %+v %v", inspection, err)
			}
			if !reflect.DeepEqual(target.store.assets(), target.installed) {
				t.Fatal("inspection changed installed assets")
			}
			report, err := target.manager.Import(context.Background(), dest)
			if err != nil {
				t.Fatal(err)
			}
			if report.TargetVerified || !report.RequiresWarmup || len(report.Installed) != len(source.ids) {
				t.Fatalf("import claimed readiness or lost assets: %+v", report)
			}
			cfg := target.store.config()
			if cfg.PublicBind != "127.0.0.1:8787" || cfg.PublicURL != "" || cfg.TLSCert != "" || cfg.TLSKey != "" || cfg.Backend != "cpu" || cfg.AutoResume || cfg.Access != "qr" || cfg.Online != (OnlineConfig{}) {
				t.Fatalf("import enabled source network/provider state: %+v", cfg)
			}
			if cfg.SpeakerPIN != before.SpeakerPIN || cfg.ListenerPIN != before.ListenerPIN || cfg.TranslationModel != source.ids[0] || cfg.STTModel != source.ids[1] {
				t.Fatal("model selections or target-local PINs changed incorrectly")
			}
			var secret []byte
			if err := target.store.get("settings", "online-secret", &secret); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("old target online secret survived offline import: %v", err)
			}
			for _, asset := range report.Installed {
				folder := "models"
				if strings.HasPrefix(asset.ID, "runtime-") {
					folder = "runtimes"
				}
				profile, _ := target.assets.getArtifact(asset.ID)
				if artifactBundle(profile) {
					if err := verifyArtifactBundle(context.Background(), asset.Path); err != nil {
						t.Fatal(err)
					}
				} else if err := verifyModel(context.Background(), asset.Path, asset.SHA256, asset.Bytes, nil); err != nil {
					t.Fatal(err)
				}
				if !engineManagedPath(target.store.dir, folder, asset.Path) || strings.HasPrefix(asset.Path, source.store.dir) {
					t.Fatalf("asset path was not rewritten into target managed root: %s", asset.Path)
				}
			}
			for _, asset := range target.installed {
				if _, err := os.Stat(asset.Path); err != nil {
					t.Fatalf("old working file was destroyed: %v", err)
				}
			}
			var oldScript map[string]string
			if err := target.store.get("scripts", collision, &oldScript); err != nil || oldScript["text"] != "target preserved" {
				t.Fatal("existing target knowledge overwritten")
			}
			scripts, err := target.store.all("scripts")
			if err != nil || len(scripts) != 2 {
				t.Fatalf("script ID merge failed: %d %v", len(scripts), err)
			}
			lessons, err := target.store.all("lessons")
			if err != nil || len(lessons) != 1 {
				t.Fatalf("unapproved lessons imported or approved lost: %d %v", len(lessons), err)
			}
			var lesson Lesson
			if err := json.Unmarshal(lessons[0], &lesson); err != nil || lesson.Status != "approved" || lesson.ID == strings.Repeat("d", 32) {
				t.Fatal("approved lesson identity not remapped")
			}
			if err := target.store.db.View(func(tx *bolt.Tx) error {
				if string(tx.Bucket([]byte("lesson-index")).Get(lessonKey(lesson.Source, lesson.Language))) != lesson.ID {
					return errors.New("approved lesson index missing")
				}
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			if NewAssetManager(target.store.dir, nil).Registry() == nil {
				t.Fatal("registry did not survive reload")
			}
			for _, id := range source.ids[:2] {
				if _, ok := NewAssetManager(target.store.dir, nil).getArtifact(id); !ok {
					t.Fatal("custom model profile did not persist offline")
				}
			}
			private, err := target.store.all("sessions")
			if err != nil || len(private) != 0 {
				t.Fatal("conversation migrated with environment")
			}
		})
	}
}

func TestPortableExportDefaultsAndNoClobber(t *testing.T) {
	f := portableTestOpen(t, "defaults")
	portableTestSeedSource(t, f)
	path := portableTestArchive(t, f, false)
	for _, entry := range portableTestReadZIP(t, path) {
		if entry.name == "knowledge.json" || strings.HasPrefix(entry.name, "application/") || bytes.Contains(entry.data, []byte("APPROVED-REUSABLE-CONTEXT")) {
			t.Fatal("optional context/executable exported by default")
		}
	}
	old, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.manager.Export(context.Background(), path, PortableExportOptions{Format: "zip", AssetIDs: f.ids}); err == nil {
		t.Fatal("export clobbered existing destination")
	}
	latest, _ := os.ReadFile(path)
	if !bytes.Equal(old, latest) {
		t.Fatal("failed export changed existing archive")
	}
}

func TestPortableHostileArchivesAreRejectedWithoutActivation(t *testing.T) {
	source := portableTestOpen(t, "hostile")
	base := portableTestReadZIP(t, portableTestArchive(t, source, false))
	type mutation struct {
		name string
		edit func(*testing.T, []portableTestEntry) []portableTestEntry
	}
	metadata := func(key string, value any) func(*testing.T, []portableTestEntry) []portableTestEntry {
		return func(t *testing.T, entries []portableTestEntry) []portableTestEntry {
			portableTestManifest(t, entries, func(m map[string]any) { m[key] = value })
			return entries
		}
	}
	cases := []mutation{
		{"unknown_version", metadata("appVersion", Version+"-future")},
		{"unknown_schema", metadata("schema", 999)},
		{"wrong_os", metadata("targetOS", "linux")},
		{"wrong_arch", metadata("targetArch", "arm64")},
		{"same_size_tamper", func(t *testing.T, entries []portableTestEntry) []portableTestEntry {
			for i := range entries {
				if strings.HasPrefix(entries[i].name, "models/") {
					entries[i].data[len(entries[i].data)-1] ^= 0xff
					return entries
				}
			}
			t.Fatal("fixture model missing")
			return entries
		}},
		{"unlisted_secret_payload", func(_ *testing.T, entries []portableTestEntry) []portableTestEntry {
			return append(entries, portableTestEntry{"settings/online-secret", []byte("evil"), 0600, zip.Store})
		}},
		{"duplicate_entry", func(_ *testing.T, entries []portableTestEntry) []portableTestEntry {
			return append(entries, entries[1])
		}},
		{"case_collision", func(_ *testing.T, entries []portableTestEntry) []portableTestEntry {
			e := entries[1]
			e.name = strings.ToUpper(e.name)
			return append(entries, e)
		}},
		{"symlink", func(_ *testing.T, entries []portableTestEntry) []portableTestEntry {
			for i := range entries {
				if strings.HasPrefix(entries[i].name, "models/") {
					entries[i].mode = os.ModeSymlink | 0777
					break
				}
			}
			return entries
		}},
		{"compressed_bomb", func(_ *testing.T, entries []portableTestEntry) []portableTestEntry {
			for i := range entries {
				if strings.HasPrefix(entries[i].name, "models/") {
					entries[i].method, entries[i].data = zip.Deflate, make([]byte, 1<<20)
					break
				}
			}
			return entries
		}},
		{"mutable_custom_revision", func(t *testing.T, entries []portableTestEntry) []portableTestEntry {
			changed := false
			portableTestManifest(t, entries, func(m map[string]any) {
				portableTestWalkMaps(m, func(v map[string]any) {
					if url, ok := v["url"].(string); ok && strings.Contains(url, "huggingface.co/") {
						v["url"] = strings.Replace(url, "/resolve/"+strings.Repeat("a", 40)+"/", "/resolve/main/", 1)
						changed = true
					}
				})
			})
			if !changed {
				t.Fatal("custom profile not found")
			}
			return entries
		}},
		{"builtin_runtime_profile_tamper", func(t *testing.T, entries []portableTestEntry) []portableTestEntry {
			changed := false
			portableTestManifest(t, entries, func(m map[string]any) {
				portableTestWalkMaps(m, func(v map[string]any) {
					if v["id"] == "runtime-llama-cpu" && v["sha256"] != nil {
						v["sha256"], changed = strings.Repeat("f", 64), true
					}
				})
			})
			if !changed {
				t.Fatal("runtime metadata not found")
			}
			return entries
		}},
	}
	for _, bad := range []string{"../escape.gguf", "/absolute.gguf", "C:/escape.gguf", "models/bad:stream.gguf", "models/CON.a.b", "models/trailing./model.gguf", "models/back\\slash.gguf"} {
		bad := bad
		cases = append(cases, mutation{bad, func(t *testing.T, entries []portableTestEntry) []portableTestEntry {
			old := ""
			for i := range entries {
				if strings.HasPrefix(entries[i].name, "models/") {
					old, entries[i].name = entries[i].name, bad
					break
				}
			}
			if old == "" {
				t.Fatal("model missing")
			}
			portableTestManifest(t, entries, func(m map[string]any) {
				portableTestWalkMaps(m, func(v map[string]any) {
					if v["path"] == old {
						v["path"] = bad
					}
				})
			})
			return entries
		}})
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			entries := make([]portableTestEntry, len(base))
			for i, e := range base {
				entries[i] = e
				entries[i].data = append([]byte(nil), e.data...)
			}
			entries = tc.edit(t, entries)
			path := filepath.Join(portableTestTemp(t), "hostile.zip")
			portableTestWriteZIP(t, path, entries)
			target := portableTestOpen(t, "reject")
			before := target.store.config()
			registry, _ := os.ReadFile(filepath.Join(target.store.dir, "registry.json"))
			if _, err := target.manager.Import(context.Background(), path); err == nil {
				t.Fatal("hostile archive accepted")
			}
			if !reflect.DeepEqual(target.installed, target.store.assets()) || !reflect.DeepEqual(before, target.store.config()) {
				t.Fatal("rejected archive changed active environment")
			}
			after, _ := os.ReadFile(filepath.Join(target.store.dir, "registry.json"))
			if !bytes.Equal(registry, after) {
				t.Fatal("rejected archive changed registry")
			}
		})
	}
}

func TestPortableFolderSymlinkAndUnexpectedFile(t *testing.T) {
	for _, kind := range []string{"symlink_file", "symlink_parent", "unlisted_file"} {
		t.Run(kind, func(t *testing.T) {
			source := portableTestOpen(t, "folder")
			path := filepath.Join(portableTestTemp(t), "folder-source")
			if _, err := source.manager.Export(context.Background(), path, PortableExportOptions{Format: "folder", AssetIDs: source.ids}); err != nil {
				t.Fatal(err)
			}
			model := ""
			if err := filepath.Walk(path, func(p string, info os.FileInfo, err error) error {
				if err == nil && !info.IsDir() && strings.HasSuffix(p, ".gguf") {
					model = p
				}
				return err
			}); err != nil {
				t.Fatal(err)
			}
			if model == "" {
				t.Fatal("GGUF missing")
			}
			switch kind {
			case "symlink_file":
				b, err := os.ReadFile(model)
				if err != nil {
					t.Fatal(err)
				}
				external := filepath.Join(portableTestTemp(t), "external.gguf")
				if err := os.WriteFile(external, b, 0600); err != nil {
					t.Fatal(err)
				}
				if err := os.Remove(model); err != nil {
					t.Fatal(err)
				}
				if err := os.Symlink(external, model); err != nil {
					t.Skipf("OS symlink unavailable: %v", err)
				}
			case "symlink_parent":
				parent := filepath.Dir(model)
				external := filepath.Join(portableTestTemp(t), "external-models")
				if err := os.Rename(parent, external); err != nil {
					t.Fatal(err)
				}
				if err := os.Symlink(external, parent); err != nil {
					t.Skipf("OS symlink unavailable: %v", err)
				}
			case "unlisted_file":
				if err := os.WriteFile(filepath.Join(path, "unlisted.txt"), []byte("extra"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			target := portableTestOpen(t, "foldertarget")
			if _, err := target.manager.Import(context.Background(), path); err == nil {
				t.Fatal("unsafe folder accepted")
			}
			if !reflect.DeepEqual(target.store.assets(), target.installed) {
				t.Fatal("unsafe folder activated")
			}
		})
	}
}

func TestPortableFailurePreservesWorkingEnvironment(t *testing.T) {
	source := portableTestOpen(t, "rollbacksource")
	path := portableTestArchive(t, source, false)
	for _, kind := range []string{"commit_failure", "canceled", "cancel_before_commit", "disk_full", "queued_job", "active_session", "asset_operation", "asset_progress", "registry_conflict"} {
		t.Run(kind, func(t *testing.T) {
			target := portableTestOpen(t, "rollbacktarget")
			before := target.store.config()
			before.Online.APIKey = "TARGET-PRESERVED-KEY"
			if err := target.store.saveConfig(before); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			switch kind {
			case "commit_failure":
				target.manager.beforeCommit = func() error { return errors.New("injected commit failure") }
			case "canceled":
				cancel()
			case "cancel_before_commit":
				target.manager.beforeCommit = func() error { cancel(); return nil }
			case "disk_full":
				target.manager.diskAvailable = func() int64 { return 1 }
			case "queued_job":
				if err := target.store.put("jobs", strings.Repeat("1", 32), Job{ID: strings.Repeat("1", 32), Kind: "translate", State: "queued", SessionID: strings.Repeat("2", 32)}); err != nil {
					t.Fatal(err)
				}
			case "active_session":
				if err := target.store.put("sessions", strings.Repeat("2", 32), Session{ID: strings.Repeat("2", 32), Kind: "private", State: "active"}); err != nil {
					t.Fatal(err)
				}
			case "asset_operation":
				target.assets.cancelMap["fixture-operation"] = func() {}
			case "asset_progress":
				target.assets.progress["fixture-operation"] = DownloadProgress{ID: "fixture-operation", State: "downloading"}
			case "registry_conflict":
				conflicting, ok := source.assets.getArtifact(source.ids[1])
				if !ok {
					t.Fatal("source profile missing")
				}
				conflicting.Name = "Existing target profile with a different name"
				target.assets.registry = append(target.assets.registry, conflicting)
				target.assets.customRegistry = append(target.assets.customRegistry, conflicting)
				if err := target.assets.saveRegistry(); err != nil {
					t.Fatal(err)
				}
			}
			registry, _ := os.ReadFile(filepath.Join(target.store.dir, "registry.json"))
			beforeRegistry := target.assets.Registry()
			if _, err := target.manager.Import(ctx, path); err == nil {
				t.Fatal("unsafe/failed import reported success")
			}
			if !reflect.DeepEqual(target.installed, target.store.assets()) || !reflect.DeepEqual(before, target.store.config()) {
				t.Fatal("failed import changed working environment")
			}
			after, _ := os.ReadFile(filepath.Join(target.store.dir, "registry.json"))
			if !bytes.Equal(registry, after) {
				t.Fatal("failed import changed registry file")
			}
			if !reflect.DeepEqual(beforeRegistry, target.assets.Registry()) {
				t.Fatal("failed import changed in-memory profiles")
			}
			for _, asset := range target.installed {
				if _, err := os.Stat(asset.Path); err != nil {
					t.Fatal("failed import removed old asset")
				}
			}
		})
	}
}

func TestPortableClosedRoomAllowsProvenStalePrivateSession(t *testing.T) {
	source := portableTestOpen(t, "closedroomsource")
	archive := portableTestArchive(t, source, false)
	f := newClassroomFixture(t)
	private := classroomPrivate(t, f)
	w := callAdmin(t, f.a, http.MethodPost, "/rooms/"+f.room.ID+"/close", nil)
	if w.Code != http.StatusOK {
		t.Fatalf("real room closure failed: %d %s", w.Code, w.Body.String())
	}
	room, err := f.rs.room(f.room.ID)
	if err != nil || room.State != "closed" {
		t.Fatal("room closure not durable")
	}
	session, err := f.a.store.session(private.ID)
	if err != nil || session.State != "active" || session.Kind != "private" {
		t.Fatal("fixture does not represent retained private session after room close")
	}
	channel, err := f.rs.channel(private.ID)
	if err != nil || channel.RoomID != room.ID {
		t.Fatal("encrypted private channel is not linked to closed room")
	}
	// Canonicalize the development OS temporary path; no unsafe path bypass.
	canonical, err := filepath.EvalSymlinks(f.a.store.dir)
	if err != nil {
		t.Fatal(err)
	}
	f.a.store.dir, f.a.assets.dataDir = canonical, canonical
	p := NewPortableManager(f.a.store, f.a.assets)
	p.targetOS, p.targetArch = "windows", "amd64"
	p.diskAvailable = func() int64 { return 1 << 40 }
	p.bundleTreePins = source.manager.bundleTreePins // explicit synthetic archive pins, never a production bypass
	report, err := p.Import(context.Background(), archive)
	if err != nil || report.TargetVerified || !report.RequiresWarmup {
		t.Fatalf("proven closed-room stale session incorrectly blocks transfer: %+v %v", report, err)
	}
	retained, err := f.a.store.session(private.ID)
	if err != nil || retained.ID != session.ID || retained.State != session.State {
		t.Fatal("environment transfer modified retained private conversation state")
	}
}
