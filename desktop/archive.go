package main

import (
	"archive/zip"
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	bolt "go.etcd.io/bbolt"
)

func (a *App) audioFile(w http.ResponseWriter, r *http.Request, sessionID, file string) {
	if a.store.isPrivateSession(sessionID) {
		http.Error(w, "Private channel requires participant authentication", 403)
		return
	}
	path, e := a.store.recordingPath(sessionID, file)
	if e != nil {
		apiError(w, e)
		return
	}
	if !strings.HasSuffix(file, ".wav") && file != "source.pcm" {
		http.Error(w, "Not found", 404)
		return
	}
	f, e := os.Open(path)
	if e != nil {
		http.Error(w, "Not found", 404)
		return
	}
	defer f.Close()
	stat, e := f.Stat()
	if e != nil {
		apiError(w, e)
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	if file == "source.pcm" {
		if stat.Size() > 0xffffffff-36 {
			apiError(w, errors.New("WAV 크기 제한을 넘었습니다. ZIP 백업에서 원음을 사용하세요"))
			return
		}
		http.ServeContent(w, r, "source.wav", stat.ModTime(), &prefixedReader{header: wavHeader(stat.Size()), file: f, size: stat.Size()})
		return
	}
	http.ServeContent(w, r, file, stat.ModTime(), f)
}

type prefixedReader struct {
	header []byte
	file   *os.File
	size   int64
	offset int64
}

func (p *prefixedReader) Read(b []byte) (int, error) {
	total := 0
	if p.offset < int64(len(p.header)) {
		n := copy(b, p.header[p.offset:])
		total += n
		p.offset += int64(n)
		b = b[n:]
	}
	if len(b) == 0 {
		return total, nil
	}
	n, e := p.file.ReadAt(b, p.offset-int64(len(p.header)))
	p.offset += int64(n)
	return total + n, e
}
func (p *prefixedReader) Seek(off int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
		p.offset = off
	case io.SeekCurrent:
		p.offset += off
	case io.SeekEnd:
		p.offset = p.size + int64(len(p.header)) + off
	default:
		return 0, errors.New("seek")
	}
	if p.offset < 0 {
		return 0, errors.New("seek")
	}
	return p.offset, nil
}
func (a *App) walkLines(sessionID string, fn func(Line) error) error {
	var cursor uint64
	for {
		lines, e := a.store.lines(sessionID, cursor, 1000)
		if e != nil {
			return e
		}
		if len(lines) == 0 {
			return nil
		}
		for _, l := range lines {
			if e := fn(l); e != nil {
				return e
			}
			cursor = l.Sequence
		}
		if len(lines) < 1000 {
			return nil
		}
	}
}
func (a *App) exportSession(w http.ResponseWriter, r *http.Request, id string) {
	if a.store.isPrivateSession(id) {
		http.Error(w, "Private channel requires participant authentication", 403)
		return
	}
	s, e := a.store.session(id)
	if e != nil {
		http.Error(w, "Not found", 404)
		return
	}
	s.Token = ""
	format := r.URL.Query().Get("format")
	if format == "" {
		format = "json"
	}
	if format != "json" && format != "txt" && format != "md" && format != "srt" && format != "zip" {
		apiError(w, errors.New("지원되는 형식은 JSON/TXT/Markdown/SRT/ZIP입니다"))
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="MCastTalk-`+id+`.`+format+`"`)
	if format == "zip" {
		w.Header().Set("Content-Type", "application/zip")
		z := zip.NewWriter(w)
		defer z.Close()
		item, e := z.Create("session.json")
		if e != nil {
			return
		}
		_ = json.NewEncoder(item).Encode(s)
		item, e = z.Create("transcripts.jsonl")
		if e != nil {
			return
		}
		_ = a.walkLines(id, func(l Line) error { return json.NewEncoder(item).Encode(l) })
		dir := filepath.Join(a.store.dir, "recordings", id)
		files, _ := os.ReadDir(dir)
		for _, f := range files {
			if f.IsDir() || f.Type()&os.ModeSymlink != 0 {
				continue
			}
			input, e := os.Open(filepath.Join(dir, f.Name()))
			if e != nil {
				continue
			}
			stat, e := input.Stat()
			if e != nil {
				input.Close()
				continue
			}
			writer, e := z.Create("audio/" + f.Name())
			if e == nil {
				_, _ = io.CopyN(writer, input, stat.Size())
			}
			input.Close()
		}
		return
	}
	if format == "json" {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		io.WriteString(w, "{\"version\":\""+Version+"\",\"session\":")
		raw, _ := json.Marshal(s)
		w.Write(raw)
		io.WriteString(w, ",\"lines\":[")
		first := true
		_ = a.walkLines(id, func(l Line) error {
			if !first {
				io.WriteString(w, ",")
			}
			first = false
			raw, e := json.Marshal(l)
			if e != nil {
				return e
			}
			_, e = w.Write(raw)
			return e
		})
		io.WriteString(w, "]}")
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	index := 0
	_ = a.walkLines(id, func(l Line) error {
		index++
		if format == "srt" {
			start := l.CapturedAt.Sub(s.StartedAt).Milliseconds()
			if start < 0 {
				start = 0
			}
			fmt.Fprintf(w, "%d\n%s --> %s\n", index, srtTime(start), srtTime(start+4000))
		} else {
			fmt.Fprintf(w, "%s\n", l.CapturedAt.Format(time.RFC3339))
		}
		fmt.Fprintln(w, l.SourceText)
		keys := []string{}
		for k := range l.Translations {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			fmt.Fprintf(w, "[%s] %s\n", k, l.Translations[k])
		}
		fmt.Fprintln(w)
		return nil
	})
}
func srtTime(ms int64) string {
	return fmt.Sprintf("%02d:%02d:%02d,%03d", ms/3600000, (ms/60000)%60, (ms/1000)%60, ms%1000)
}
func (a *App) backup(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="MCastTalk-desktop-backup.zip"`)
	z := zip.NewWriter(w)
	defer z.Close()
	writer, e := z.Create("manifest.json")
	if e != nil {
		return
	}
	_ = json.NewEncoder(writer).Encode(map[string]any{"schema": 1, "version": Version, "containsSecrets": false, "privateChannelsExcluded": true})
	_ = a.store.db.View(func(tx *bolt.Tx) error {
		for _, name := range []string{"sessions", "lines", "jobs", "glossary", "scripts", "lessons"} {
			writer, e := z.Create(name + ".jsonl")
			if e != nil {
				return e
			}
			enc := json.NewEncoder(writer)
			if e = tx.Bucket([]byte(name)).ForEach(func(k, v []byte) error {
				if name == "sessions" {
					var s Session
					if e := json.Unmarshal(v, &s); e != nil {
						return e
					}
					if s.Kind == "private" {
						return nil
					}
					s.Token = ""
					v, _ = json.Marshal(s)
				}
				if name == "jobs" {
					var j Job
					if e := json.Unmarshal(v, &j); e != nil {
						return e
					}
					if privateSessionTx(tx, j.SessionID) {
						return nil
					}
					j.Path = filepath.Base(j.Path)
					v, _ = json.Marshal(j)
				}
				if name == "lines" {
					var line Line
					if e := a.store.decodeRecord("lines", string(k), v, &line); e != nil {
						return e
					}
					if privateSessionTx(tx, line.SessionID) {
						return nil
					}
					v, _ = json.Marshal(line)
				}
				return enc.Encode(map[string]any{"key": string(k), "value": json.RawMessage(v)})
			}); e != nil {
				return e
			}
		}
		return nil
	})
	root := filepath.Join(a.store.dir, "recordings")
	_ = filepath.WalkDir(root, func(path string, d os.DirEntry, e error) error {
		if e != nil || d.IsDir() || d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		rel, e := filepath.Rel(root, path)
		if e != nil {
			return e
		}
		parts := strings.Split(filepath.ToSlash(rel), "/")
		if len(parts) != 2 || !validID(parts[0]) {
			return nil
		}
		if a.store.isPrivateSession(parts[0]) {
			return nil
		}
		f, e := os.Open(path)
		if e != nil {
			return e
		}
		defer f.Close()
		stat, e := f.Stat()
		if e != nil {
			return e
		}
		out, e := z.Create("recordings/" + filepath.ToSlash(rel))
		if e != nil {
			return e
		}
		_, e = io.CopyN(out, f, stat.Size())
		return e
	})
}
func (a *App) restore(w http.ResponseWriter, r *http.Request) {
	if a.pipeline.current() != nil {
		apiError(w, errors.New("자료 가져오기 전에 현재 작업을 종료하세요"))
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2*1024*1024*1024)
	if e := r.ParseMultipartForm(4 * 1024 * 1024); e != nil {
		apiError(w, e)
		return
	}
	defer r.MultipartForm.RemoveAll()
	f, _, e := r.FormFile("backup")
	if e != nil {
		apiError(w, e)
		return
	}
	defer f.Close()
	stage, e := os.MkdirTemp(a.store.dir, "restore-")
	if e != nil {
		apiError(w, e)
		return
	}
	defer os.RemoveAll(stage)
	path := filepath.Join(stage, "upload.zip")
	out, e := os.Create(path)
	if e != nil {
		apiError(w, e)
		return
	}
	_, e = io.Copy(out, f)
	closeE := out.Close()
	if e != nil || closeE != nil {
		apiError(w, errors.New("백업 업로드를 보관하지 못했습니다"))
		return
	}
	reader, e := zip.OpenReader(path)
	if e != nil {
		apiError(w, e)
		return
	}
	defer reader.Close()
	manifestOK := false
	total := uint64(0)
	for _, entry := range reader.File {
		total += entry.UncompressedSize64
		if total > 4*1024*1024*1024 || entry.Mode()&os.ModeSymlink != 0 {
			apiError(w, errors.New("백업의 크기 또는 파일 유형이 허용 범위를 넘습니다"))
			return
		}
		if entry.Name == "manifest.json" {
			r, e := entry.Open()
			if e != nil {
				apiError(w, e)
				return
			}
			var m struct {
				Schema          int    `json:"schema"`
				Version         string `json:"version"`
				ContainsSecrets bool   `json:"containsSecrets"`
			}
			e = json.NewDecoder(io.LimitReader(r, 4096)).Decode(&m)
			r.Close()
			manifestOK = e == nil && m.Schema == 1 && !m.ContainsSecrets
		}
	}
	if !manifestOK {
		apiError(w, errors.New("지원되는 MCastTalk 백업이 아닙니다"))
		return
	}
	// Stage and validate every entry before the single database merge. Existing data remains intact.
	rows := map[string]map[string]json.RawMessage{}
	for _, entry := range reader.File {
		if entry.Name == "manifest.json" {
			continue
		}
		if strings.HasPrefix(entry.Name, "recordings/") {
			parts := strings.Split(entry.Name, "/")
			if len(parts) != 3 || !validID(parts[1]) || strings.ContainsAny(parts[2], "\\") || strings.HasPrefix(parts[2], ".") || (!strings.HasSuffix(parts[2], ".wav") && parts[2] != "source.pcm") {
				apiError(w, errors.New("잘못된 백업 파일 경로"))
				return
			}
			dest := filepath.Join(stage, "recordings", parts[1], parts[2])
			if e = os.MkdirAll(filepath.Dir(dest), 0700); e != nil {
				apiError(w, e)
				return
			}
			rd, e := entry.Open()
			if e != nil {
				apiError(w, e)
				return
			}
			wr, e := os.Create(dest)
			if e != nil {
				rd.Close()
				apiError(w, e)
				return
			}
			_, e = io.Copy(wr, rd)
			if e == nil {
				e = wr.Sync()
			}
			wr.Close()
			rd.Close()
			if e != nil {
				apiError(w, e)
				return
			}
			continue
		}
		bucket := strings.TrimSuffix(entry.Name, ".jsonl")
		allowed := bucket == "sessions" || bucket == "lines" || bucket == "jobs" || bucket == "glossary" || bucket == "scripts" || bucket == "lessons"
		if !allowed || entry.Name != bucket+".jsonl" {
			apiError(w, errors.New("허용되지 않은 백업 항목"))
			return
		}
		rd, e := entry.Open()
		if e != nil {
			apiError(w, e)
			return
		}
		scanner := bufio.NewScanner(rd)
		scanner.Buffer(make([]byte, 4096), 1024*1024)
		if rows[bucket] == nil {
			rows[bucket] = map[string]json.RawMessage{}
		}
		for scanner.Scan() {
			var row struct {
				Key   string          `json:"key"`
				Value json.RawMessage `json:"value"`
			}
			if e = json.Unmarshal(scanner.Bytes(), &row); e != nil || !validBackupRow(bucket, row.Key, row.Value) {
				rd.Close()
				apiError(w, errors.New("잘못된 백업 자료"))
				return
			}
			rows[bucket][row.Key] = row.Value
		}
		e = scanner.Err()
		rd.Close()
		if e != nil {
			apiError(w, e)
			return
		}
	}
	for id := range rows["sessions"] {
		if _, e := a.store.session(id); e == nil {
			apiError(w, errors.New("이미 보관된 작업과 중복됩니다. 기존 자료를 덮어쓰지 않았습니다"))
			return
		}
	}
	stagedAudio := filepath.Join(stage, "recordings")
	entries, _ := os.ReadDir(stagedAudio)
	for _, d := range entries {
		if _, ok := rows["sessions"][d.Name()]; !ok {
			apiError(w, errors.New("녹음의 작업 정보가 없습니다"))
			return
		}
		dest := filepath.Join(a.store.dir, "recordings", d.Name())
		if _, err := os.Stat(dest); err == nil {
			apiError(w, errors.New("녹음 폴더가 이미 존재합니다"))
			return
		}
		if e = os.Rename(filepath.Join(stagedAudio, d.Name()), dest); e != nil {
			apiError(w, e)
			return
		}
	}
	e = a.store.db.Update(func(tx *bolt.Tx) error {
		for name, items := range rows {
			for key, value := range items {
				switch name {
				case "sessions":
					var s Session
					_ = json.Unmarshal(value, &s)
					s.Token = newID() + newID()
					s.State = "stopped"
					value, _ = json.Marshal(s)
				case "jobs":
					var j Job
					_ = json.Unmarshal(value, &j)
					j.State = "imported"
					if j.Path != "." && j.Path != "" {
						j.Path = filepath.Join(a.store.dir, "recordings", j.SessionID, filepath.Base(j.Path))
					}
					value, _ = json.Marshal(j)
				}
				if e := tx.Bucket([]byte(name)).Put([]byte(key), value); e != nil {
					return e
				}
			}
		}
		return a.store.rebuildLineIndex(tx)
	})
	if e != nil {
		apiError(w, e)
		return
	}
	jsonReply(w, 200, map[string]any{"importedSessions": len(rows["sessions"]), "state": "merged"})
}
func validBackupRow(bucket, key string, v []byte) bool {
	if bucket == "glossary" {
		var terms []GlossaryTerm
		return key == "terms" && json.Unmarshal(v, &terms) == nil
	}
	if !validID(key) || len(v) > 1024*1024 {
		return false
	}
	switch bucket {
	case "sessions":
		var s Session
		return json.Unmarshal(v, &s) == nil && s.ID == key && languagePattern.MatchString(s.SourceLanguage)
	case "lines":
		var l Line
		return json.Unmarshal(v, &l) == nil && l.ID == key && validID(l.SessionID) && len(l.SourceText) <= 8192
	case "jobs":
		var j Job
		return json.Unmarshal(v, &j) == nil && j.ID == key && validID(j.SessionID) && !strings.ContainsAny(j.Path, "/\\")
	case "lessons":
		var l Lesson
		return json.Unmarshal(v, &l) == nil && l.ID == key && languagePattern.MatchString(l.Language)
	case "scripts":
		var s map[string]string
		return json.Unmarshal(v, &s) == nil && s["id"] == key
	}
	return false
}

type replaySegment struct {
	Part       int   `json:"part"`
	Segment    int   `json:"segment"`
	Bytes      int64 `json:"bytes"`
	StartByte  int64 `json:"startByte"`
	SampleRate int   `json:"sampleRate"`
	file       string
	skip       int64
}

func (a *App) replaySegments(s *Session, channel string) ([]replaySegment, error) {
	out := []replaySegment{}
	offset := int64(0)
	if channel == "source" {
		path, e := a.store.recordingPath(s.ID, "source.pcm")
		if e != nil {
			return nil, e
		}
		if st, e := os.Stat(path); e == nil {
			out = append(out, replaySegment{Bytes: st.Size() - st.Size()%2, SampleRate: 16000, file: path})
			return out, nil
		}
	}
	e := a.walkLines(s.ID, func(l Line) error {
		file := l.Audio[channel]
		if file == "" {
			return nil
		}
		path, e := a.store.recordingPath(s.ID, file)
		if e != nil {
			return e
		}
		st, e := os.Stat(path)
		if e != nil {
			return nil
		}
		size := st.Size() - 44
		if size < 0 {
			return nil
		}
		out = append(out, replaySegment{Segment: len(out), Bytes: size, StartByte: offset, SampleRate: 16000, file: path, skip: 44})
		offset += size
		return nil
	})
	return out, e
}
func (a *App) publicReplay(w http.ResponseWriter, r *http.Request, s *Session) {
	channel := r.URL.Query().Get("channel")
	if channel == "" {
		channel = "source"
	}
	segments, e := a.replaySegments(s, channel)
	if e != nil {
		apiError(w, e)
		return
	}
	if r.URL.Path == "/api/replay" {
		jsonReply(w, 200, map[string]any{"state": s.State, "recordingGaps": s.GapCount, "storageFailed": false, "timeline": "RECORDED_AUDIO_EXCLUDING_PAUSES", "segments": segments})
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/replay/"), "/")
	if len(parts) != 2 || parts[0] != "0" {
		http.Error(w, "Not found", 404)
		return
	}
	n, e := strconv.Atoi(parts[1])
	if e != nil || n < 0 || n >= len(segments) {
		http.Error(w, "Not found", 404)
		return
	}
	offset, e1 := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	count, e2 := strconv.Atoi(r.URL.Query().Get("count"))
	segment := segments[n]
	if e1 != nil || e2 != nil || offset < 0 || offset%2 != 0 || count < 2 || count > 262144 || count%2 != 0 || offset > segment.Bytes-int64(count) {
		http.Error(w, "Invalid range", 416)
		return
	}
	f, e := os.Open(segment.file)
	if e != nil {
		apiError(w, e)
		return
	}
	defer f.Close()
	b := make([]byte, count)
	if _, e = f.ReadAt(b, segment.skip+offset); e != nil {
		apiError(w, e)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Write(b)
}

var _ = bytes.NewReader
