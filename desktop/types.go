package main

import "time"

const Version = "0.2.49-desktop"

type Config struct {
	PublicBind       string       `json:"publicBind"`
	PublicURL        string       `json:"publicURL"`
	TLSCert          string       `json:"tlsCert"`
	TLSKey           string       `json:"tlsKey"`
	MaxListeners     int          `json:"maxListeners"`
	Access           string       `json:"access"`
	ListenerPIN      string       `json:"listenerPin"`
	SpeakerPIN       string       `json:"speakerPin"`
	SourceLanguage   string       `json:"sourceLanguage"`
	TargetLanguages  []string     `json:"targetLanguages"`
	TranslationModel string       `json:"translationModel"`
	STTModel         string       `json:"sttModel"`
	Backend          string       `json:"backend"`
	AutoResume       bool         `json:"autoResume"`
	Online           OnlineConfig `json:"online"`
}
type OnlineConfig struct {
	Endpoint string `json:"endpoint"`
	Model    string `json:"model"`
	APIKey   string `json:"apiKey,omitempty"`
	Consent  bool   `json:"consent"`
}

func defaultConfig() Config {
	return Config{PublicBind: "127.0.0.1:8787", MaxListeners: 512, Access: "qr", SourceLanguage: "ko", TargetLanguages: []string{"ko", "en", "ja", "zh", "es"}, TranslationModel: "qwen3-4b-q4", STTModel: "whisper-small-q5", Backend: "cpu", AutoResume: true}
}

type Artifact struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Task         string   `json:"task"` // translation, stt, runtime
	Family       string   `json:"family"`
	Prompt       string   `json:"prompt"`
	Format       string   `json:"format"`
	URL          string   `json:"url"`
	SHA256       string   `json:"sha256"`
	TreeSHA256   string   `json:"treeSHA256,omitempty"`
	Bytes        int64    `json:"bytes"`
	RAMGB        int      `json:"ramGB"`
	VRAMGB       int      `json:"vramGB"`
	Context      int      `json:"context"`
	License      string   `json:"license"`
	Source       string   `json:"source"`
	Backends     []string `json:"backends"`
	Experimental bool     `json:"experimental"`
}
type InstalledAsset struct {
	ID          string    `json:"id"`
	Path        string    `json:"path"`
	SHA256      string    `json:"sha256"`
	Bytes       int64     `json:"bytes"`
	InstalledAt time.Time `json:"installedAt"`
}
type Device struct {
	Name     string  `json:"name"`
	Kind     string  `json:"kind"`
	Vendor   string  `json:"vendor"`
	VRAMGB   float64 `json:"vramGB"`
	Verified bool    `json:"verified"`
}
type ModelRecommendation struct {
	ID      string `json:"id"`
	Status  string `json:"status"`
	Backend string `json:"backend"`
	Reason  string `json:"reason"`
}
type Diagnostic struct {
	OS               string                `json:"os"`
	Arch             string                `json:"arch"`
	CPU              string                `json:"cpu"`
	Cores            int                   `json:"cores"`
	RAMGB            float64               `json:"ramGB"`
	AvailableRAMGB   float64               `json:"availableRamGB"`
	FreeDiskGB       float64               `json:"freeDiskGB"`
	Devices          []Device              `json:"devices"`
	Recommendations  []ModelRecommendation `json:"recommendations"`
	Warnings         []string              `json:"warnings"`
	MobileEquivalent bool                  `json:"mobileEquivalent"`
	Measured         bool                  `json:"measured"`
	CheckedAt        time.Time             `json:"checkedAt"`
}
type EngineStatus struct {
	TranslationReady bool         `json:"translationReady"`
	STTReady         bool         `json:"sttReady"`
	TTSReady         bool         `json:"ttsReady"`
	Backend          string       `json:"backend"`
	Error            string       `json:"error"`
	StartupStage     string       `json:"startupStage,omitempty"`
	StartupStartedAt time.Time    `json:"startupStartedAt,omitempty"`
	StartupMillis    int64        `json:"startupMillis,omitempty"`
	StartupChecks    []setupCheck `json:"startupChecks,omitempty"`
}
type GlossaryTerm struct {
	Source   string `json:"source"`
	Target   string `json:"target"`
	Language string `json:"language"`
}
type Session struct {
	ID             string    `json:"id"`
	Kind           string    `json:"kind"`
	Title          string    `json:"title"`
	State          string    `json:"state"`
	SourceLanguage string    `json:"sourceLanguage"`
	Targets        []string  `json:"targets"`
	Token          string    `json:"token,omitempty"`
	StartedAt      time.Time `json:"startedAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
	GapCount       int       `json:"gapCount"`
}
type Line struct {
	SpeechMetadata
	ID                       string            `json:"id"`
	SessionID                string            `json:"sessionId"`
	Sequence                 uint64            `json:"sequence"`
	Revision                 uint64            `json:"revision"`
	SourceText               string            `json:"sourceText"`
	IsFinal                  bool              `json:"isFinal"`
	CapturedAt               time.Time         `json:"capturedAt"`
	Translations             map[string]string `json:"translations"`
	TranslationLatencyMillis map[string]int64  `json:"translationLatencyMillis"`
	FirstAudioLatencyMillis  map[string]int64  `json:"firstAudioLatencyMillis"`
	SynthesisLatencyMillis   map[string]int64  `json:"synthesisLatencyMillis"`
	Errors                   map[string]string `json:"errors"`
	Audio                    map[string]string `json:"audio"`
}
type Job struct {
	SpeechMetadata
	ID            string    `json:"id"`
	SessionID     string    `json:"sessionId"`
	LineID        string    `json:"lineId"`
	Kind          string    `json:"kind"`
	Path          string    `json:"path,omitempty"`
	State         string    `json:"state"`
	Attempts      int       `json:"attempts"`
	Error         string    `json:"error,omitempty"`
	CreatedAt     time.Time `json:"createdAt"`
	PayloadSHA256 string    `json:"payloadSha256,omitempty"`
}

// SpeechMetadata keeps individual input language and identity through durable jobs.
type SpeechMetadata struct {
	SourceLanguage string `json:"sourceLanguage,omitempty"`
	SpeakerID      string `json:"speakerId,omitempty"`
	SpeakerName    string `json:"speakerName,omitempty"`
	Role           string `json:"role,omitempty"`
}
type DownloadProgress struct {
	ID       string `json:"id"`
	State    string `json:"state"`
	Received int64  `json:"received"`
	Total    int64  `json:"total"`
	Error    string `json:"error,omitempty"`
}
type Lesson struct {
	ID        string    `json:"id"`
	Source    string    `json:"source"`
	Language  string    `json:"language"`
	Before    string    `json:"before"`
	Proposed  string    `json:"proposed"`
	Status    string    `json:"status"`
	CreatedAt time.Time `json:"createdAt"`
}
