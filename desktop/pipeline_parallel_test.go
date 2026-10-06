package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	bolt "go.etcd.io/bbolt"
)

// These small fixtures prove scheduling/routing/durability, not model RTF,
// pronunciation quality, Windows execution or a 300-listener inference SLA.
type parallelPipelineEngine struct {
	*fixtureEngine
	traceMu       sync.Mutex
	calls         map[string]int
	active, peak  int
	entered       chan string
	translateHook func(context.Context, string) error
	synthesisHook func(context.Context, string) ([]byte, error)
}

func (e *parallelPipelineEngine) call(stage, lang string, private bool) func() {
	e.traceMu.Lock()
	if e.calls == nil {
		e.calls = map[string]int{}
	}
	e.calls[fmt.Sprintf("%s/%s/%t", stage, lang, private)]++
	e.active++
	e.peak = max(e.peak, e.active)
	e.traceMu.Unlock()
	if e.entered != nil {
		e.entered <- stage + "/" + lang
	}
	return func() { e.traceMu.Lock(); e.active--; e.traceMu.Unlock() }
}
func (e *parallelPipelineEngine) translate(ctx context.Context, text, lang string, private bool) (string, error) {
	defer e.call("translate", lang, private)()
	if e.translateHook != nil {
		if err := e.translateHook(ctx, lang); err != nil {
			return "", err
		}
	}
	return lang + ":" + text, nil
}
func (e *parallelPipelineEngine) Translate(ctx context.Context, text, source, lang, recent string, terms []GlossaryTerm) (string, error) {
	return e.translate(ctx, text, lang, false)
}
func (e *parallelPipelineEngine) synthesize(ctx context.Context, lang string, private bool) ([]byte, error) {
	defer e.call("tts", lang, private)()
	if e.synthesisHook != nil {
		return e.synthesisHook(ctx, lang)
	}
	return bytes.Repeat([]byte{1, 2}, 32), nil
}
func (e *parallelPipelineEngine) Synthesize(ctx context.Context, text, lang string) ([]byte, error) {
	return e.synthesize(ctx, lang, false)
}
func (e *parallelPipelineEngine) snapshot() (map[string]int, int) {
	e.traceMu.Lock()
	defer e.traceMu.Unlock()
	copy := map[string]int{}
	for k, v := range e.calls {
		copy[k] = v
	}
	return copy, e.peak
}

type parallelTranslatePrivateEngine struct{ *parallelPipelineEngine }

func (e *parallelTranslatePrivateEngine) TranslatePrivate(ctx context.Context, text, source, lang, recent string, terms []GlossaryTerm) (string, error) {
	return e.translate(ctx, text, lang, true)
}

type parallelFullyPrivateEngine struct {
	*parallelTranslatePrivateEngine
}

func (e *parallelFullyPrivateEngine) SynthesizePrivate(ctx context.Context, text, lang string) ([]byte, error) {
	return e.synthesize(ctx, lang, true)
}
func parallelRun(p *Pipeline, l Line) <-chan error {
	done := make(chan error, 1)
	go func() {
		done <- p.processTranslation(Job{ID: l.ID, LineID: l.ID, SessionID: l.SessionID, Kind: "translate"})
	}()
	return done
}
func parallelAwait(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(3 * time.Second):
		t.Fatal("bounded pipeline did not finish")
		return nil
	}
}
func parallelLine(t *testing.T, a *App, s Session, audio bool) Line {
	t.Helper()
	name := ""
	if audio {
		name = "original.wav"
		if err := a.store.writeRecording(s.ID, name, wavBytes(bytes.Repeat([]byte{3, 4}, 32))); err != nil {
			t.Fatal(err)
		}
	}
	l, err := a.store.newInputLine(s.ID, "fixture utterance 123", newID(), SpeechMetadata{SourceLanguage: s.SourceLanguage}, name, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func TestPipelineParallelBoundedUniqueLanguagesAndSourceReuse(t *testing.T) {
	a, _ := testApp(t)
	release := make(chan struct{})
	var once sync.Once
	t.Cleanup(func() { once.Do(func() { close(release) }) })
	e := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}, entered: make(chan string, 32), translateHook: func(ctx context.Context, lang string) error {
		select {
		case <-release:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}}
	a.pipeline.engine = e
	s := testSession(t, a, []string{"en", "ja", "zh", "es", "ko", "en"})
	for i := 0; i < 300; i++ {
		if _, err := a.pipeline.hub.subscribe([]string{"ko", "en", "ja", "zh", "es"}[i%5], 512); err != nil {
			t.Fatal(err)
		}
	}
	l := parallelLine(t, a, s, true)
	done := parallelRun(a.pipeline, l)
	started := map[string]bool{}
	for i := 0; i < 2; i++ {
		select {
		case event := <-e.entered:
			started[event] = true
		case <-time.After(3 * time.Second):
			t.Fatal("two language workers did not enter together")
		}
	}
	if !started["translate/en"] || !started["translate/ja"] {
		t.Fatalf("unexpected first tasks: %v", started)
	}
	select {
	case event := <-e.entered:
		t.Fatalf("more than two blocked language tasks: %s", event)
	default:
	}
	once.Do(func() { close(release) })
	if err := parallelAwait(t, done); err != nil {
		t.Fatal(err)
	}
	calls, peak := e.snapshot()
	if peak != 2 {
		t.Fatalf("peak calls %d, want2", peak)
	}
	for _, lang := range []string{"en", "ja", "zh", "es"} {
		if calls["translate/"+lang+"/false"] != 1 || calls["tts/"+lang+"/false"] != 1 {
			t.Fatalf("listener/duplicate multiplied %s calls: %v", lang, calls)
		}
	}
	if calls["translate/ko/false"] != 0 || calls["tts/ko/false"] != 0 {
		t.Fatal("original-language inference was not reused")
	}
	var saved Line
	if err := a.store.get("lines", l.ID, &saved); err != nil {
		t.Fatal(err)
	}
	if len(saved.Audio) != 6 || saved.Audio["ko"] != saved.Audio["source"] || saved.Translations["ko"] != l.SourceText {
		t.Fatalf("results lost/source audio not reused: %+v", saved)
	}
	if err := a.pipeline.processTranslation(Job{LineID: l.ID, SessionID: s.ID}); err != nil {
		t.Fatal(err)
	}
	after, _ := e.snapshot()
	for k, v := range calls {
		if after[k] != v {
			t.Fatalf("durable retry repeated %s", k)
		}
	}
}

func TestPipelineParallelFastAudioDoesNotWaitForSlowLanguage(t *testing.T) {
	a, _ := testApp(t)
	release := make(chan struct{})
	var once sync.Once
	t.Cleanup(func() { once.Do(func() { close(release) }) })
	e := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}, entered: make(chan string, 16), synthesisHook: func(ctx context.Context, lang string) ([]byte, error) {
		if lang == "en" {
			select {
			case <-release:
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		return []byte{1, 2, 3, 4}, nil
	}}
	a.pipeline.engine = e
	s := testSession(t, a, []string{"en", "ja"})
	events := make(chan Line, 32)
	a.store.setLineNotifier(func(l Line) { events <- l })
	l := parallelLine(t, a, s, false)
	done := parallelRun(a.pipeline, l)
	deadline := time.After(3 * time.Second)
	for {
		select {
		case event := <-e.entered:
			if event == "tts/en" {
				goto blocked
			}
		case <-deadline:
			t.Fatal("slow TTS did not enter")
		}
	}
blocked:
	for {
		select {
		case update := <-events:
			if update.Audio["ja"] != "" {
				wav, err := a.store.readRecording(s.ID, update.Audio["ja"])
				if err != nil || validateWAV(wav) != nil {
					t.Fatal("fast language audio not durable before slow completion")
				}
				goto delivered
			}
		case <-deadline:
			t.Fatal("fast audio waited behind slow language")
		}
	}
delivered:
	select {
	case err := <-done:
		t.Fatalf("slow worker unexpectedly finished: %v", err)
	default:
	}
	once.Do(func() { close(release) })
	if err := parallelAwait(t, done); err != nil {
		t.Fatal(err)
	}
}

func TestPipelineParallelFailuresKeepCaptionsAndStableLanguageOrder(t *testing.T) {
	a, _ := testApp(t)
	e := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}, translateHook: func(ctx context.Context, lang string) error {
		if lang == "ja" {
			return errors.New("translation unavailable")
		}
		return nil
	}, synthesisHook: func(ctx context.Context, lang string) ([]byte, error) {
		if lang == "es" {
			return []byte{1}, nil
		}
		return []byte{1, 2}, nil
	}}
	a.pipeline.engine = e
	s := testSession(t, a, []string{"ja", "en", "es", "zh"})
	l := parallelLine(t, a, s, true)
	err := parallelAwait(t, parallelRun(a.pipeline, l))
	if err == nil || strings.Index(err.Error(), "es:") >= strings.Index(err.Error(), "ja:") {
		t.Fatalf("failures missing or unstable order: %v", err)
	}
	var saved Line
	if err := a.store.get("lines", l.ID, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.Translations["es"] == "" || saved.Audio["es"] != "" || !strings.Contains(saved.Errors["es"], "자막 저장됨") || saved.Errors["ja"] == "" || saved.Audio["en"] == "" || saved.Audio["zh"] == "" || saved.Audio["source"] == "" {
		t.Fatalf("partial results lost: %+v", saved)
	}
}

func TestPipelineParallelCancellationStopsUnstartedTargets(t *testing.T) {
	a, _ := testApp(t)
	e := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}, entered: make(chan string, 16), translateHook: func(ctx context.Context, lang string) error { <-ctx.Done(); return ctx.Err() }}
	a.pipeline.engine = e
	s := testSession(t, a, []string{"en", "ja", "zh", "es"})
	l := parallelLine(t, a, s, false)
	done := parallelRun(a.pipeline, l)
	for i := 0; i < 2; i++ {
		select {
		case <-e.entered:
		case <-time.After(3 * time.Second):
			t.Fatal("missing cancellable worker")
		}
	}
	a.pipeline.cancel()
	if err := parallelAwait(t, done); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation lost: %v", err)
	}
	calls, _ := e.snapshot()
	if calls["translate/zh/false"] != 0 || calls["translate/es/false"] != 0 {
		t.Fatalf("canceled queue started more inference: %v", calls)
	}
}

func TestPipelineParallelStoreFailureIsReported(t *testing.T) {
	a, _ := testApp(t)
	s := testSession(t, a, []string{"en"})
	l := parallelLine(t, a, s, false)
	e := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}, translateHook: func(ctx context.Context, lang string) error {
		return a.store.db.Update(func(tx *bolt.Tx) error { return tx.Bucket([]byte("lines")).Delete([]byte(l.ID)) })
	}}
	a.pipeline.engine = e
	if err := parallelAwait(t, parallelRun(a.pipeline, l)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("durable write error hidden: %v", err)
	}
	if a.pipeline.lastIssue() == "" {
		t.Fatal("durable write failure not reported")
	}
}

func TestPipelineParallelPrivateAdaptersFailClosed(t *testing.T) {
	for _, mode := range []string{"ready", "missing-translation", "missing-tts"} {
		t.Run(mode, func(t *testing.T) {
			f := newClassroomFixture(t)
			c := classroomPrivate(t, f)
			s, err := f.a.store.session(c.ID)
			if err != nil {
				t.Fatal(err)
			}
			base := &parallelPipelineEngine{fixtureEngine: &fixtureEngine{}}
			switch mode {
			case "ready":
				f.a.pipeline.engine = &parallelFullyPrivateEngine{&parallelTranslatePrivateEngine{base}}
			case "missing-translation":
				f.a.pipeline.engine = base
			case "missing-tts":
				f.a.pipeline.engine = &parallelTranslatePrivateEngine{base}
			}
			l, err := f.a.store.newSpeakerLine(s.ID, "private fixture", newID(), SpeechMetadata{SourceLanguage: "zh"})
			if err != nil {
				t.Fatal(err)
			}
			err = parallelAwait(t, parallelRun(f.a.pipeline, l))
			if (mode == "ready") != (err == nil) {
				t.Fatalf("private readiness not enforced: %v", err)
			}
			calls, _ := base.snapshot()
			for key := range calls {
				if strings.HasSuffix(key, "/false") {
					t.Fatalf("private source reached public adapter: %s", key)
				}
			}
			if mode == "ready" {
				for _, lang := range s.Targets {
					if calls["translate/"+lang+"/true"] != 1 || calls["tts/"+lang+"/true"] != 1 {
						t.Fatalf("private target not processed once: %v", calls)
					}
				}
			}
			if mode == "missing-tts" {
				var saved Line
				if err := f.a.store.get("lines", l.ID, &saved); err != nil {
					t.Fatal(err)
				}
				for _, lang := range s.Targets {
					if saved.Translations[lang] == "" || saved.Audio[lang] != "" {
						t.Fatalf("private missing-TTS captions lost: %+v", saved)
					}
				}
			}
		})
	}
}

func TestPipelineParallelRejectsMismatchedChannelJob(t *testing.T) {
	a, e := testApp(t)
	s := testSession(t, a, []string{"en"})
	l := parallelLine(t, a, s, false)
	other := s
	other.ID = newID()
	if err := a.store.put("sessions", other.ID, other); err != nil {
		t.Fatal(err)
	}
	if err := a.pipeline.processTranslation(Job{LineID: l.ID, SessionID: other.ID}); err == nil {
		t.Fatal("line context could be mixed across channels")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if len(e.calls) != 0 {
		t.Fatal("mismatched job reached engine")
	}
}
