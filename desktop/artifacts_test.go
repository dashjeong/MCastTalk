package main

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type mockTransport struct {
	roundTripFunc func(req *http.Request) (*http.Response, error)
}

func (m *mockTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return m.roundTripFunc(req)
}

func TestArtifacts_Register(t *testing.T) {
	origTransport := http.DefaultClient.Transport
	defer func() { http.DefaultClient.Transport = origTransport }()

	http.DefaultClient.Transport = &mockTransport{
		roundTripFunc: func(req *http.Request) (*http.Response, error) {
			if strings.Contains(req.URL.Path, "myorg/mymodel/revision/main") {
				body := fmt.Sprintf(`{"sha":%q,"siblings":[{"rfilename":"model.gguf","lfs":{"sha256":%q,"size":4096}}]}`, strings.Repeat("a", 40), strings.Repeat("b", 64))
				return &http.Response{
					StatusCode: 200,
					Body:       io.NopCloser(bytes.NewBufferString(body)),
					Header:     make(http.Header),
				}, nil
			}
			return &http.Response{StatusCode: 404, Body: io.NopCloser(bytes.NewBufferString(""))}, nil
		},
	}

	dir := t.TempDir()
	am := NewAssetManager(dir, nil)
	prof := Artifact{Format: "gguf", Task: "translation", Prompt: "chat"}
	art, err := am.Register(context.Background(), "myorg/mymodel", "main", "model.gguf", prof)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if art.SHA256 != strings.Repeat("b", 64) || !strings.Contains(art.URL, "/resolve/"+strings.Repeat("a", 40)+"/") {
		t.Fatalf("HF file not pinned to verified commit: %+v", art)
	}
	reloaded := NewAssetManager(dir, nil)
	if _, ok := reloaded.getArtifact(art.ID); !ok {
		t.Fatal("registry did not persist")
	}
}

func TestArtifacts_DownloadResumeAndHash(t *testing.T) {
	dir := t.TempDir()
	am := NewAssetManager(dir, nil)

	data := []byte("GGUF_dummy_data_for_testing_hash_download")
	hash := sha256.Sum256(data)
	hashHex := hex.EncodeToString(hash[:])

	am.registry = append(am.registry, Artifact{
		ID:     "test-download",
		URL:    "http://fake.url/model.gguf",
		Bytes:  int64(len(data)),
		SHA256: hashHex,
		Format: "gguf",
	})

	origTransport := http.DefaultClient.Transport
	defer func() { http.DefaultClient.Transport = origTransport }()

	reqCount := 0
	http.DefaultClient.Transport = &mockTransport{
		roundTripFunc: func(req *http.Request) (*http.Response, error) {
			reqCount++
			if req.Header.Get("Range") != "" {
				cr := fmt.Sprintf("bytes 10-%d/%d", len(data)-1, len(data))
				resp := &http.Response{
					StatusCode: 206,
					Body:       io.NopCloser(bytes.NewBuffer(data[10:])),
					Header:     make(http.Header),
				}
				resp.Header.Set("Content-Range", cr)
				return resp, nil
			}
			return &http.Response{
				StatusCode: 200,
				Body:       io.NopCloser(bytes.NewBuffer(data[:10])),
				Header:     make(http.Header),
			}, nil
		},
	}

	err := am.Download(context.Background(), "test-download")
	if err == nil {
		t.Fatal("expected error on partial download")
	}

	err = am.Download(context.Background(), "test-download")
	if err != nil {
		t.Fatalf("expected success on resume, got %v", err)
	}

	if reqCount != 2 {
		t.Fatalf("expected 2 requests, got %d", reqCount)
	}
}

func TestArtifacts_ImportZipTraversal(t *testing.T) {
	dir := t.TempDir()
	am := NewAssetManager(dir, nil)

	zipPath := filepath.Join(dir, "malicious.zip")
	f, _ := os.Create(zipPath)
	zw := zip.NewWriter(f)
	w, _ := zw.Create("../../../etc/passwd")
	w.Write([]byte("fake"))
	zw.Close()
	f.Close()

	hashBytes, _ := os.ReadFile(zipPath)
	h := sha256.Sum256(hashBytes)

	am.registry = append(am.registry, Artifact{
		ID:     "malicious-zip",
		Format: "zip",
		Bytes:  int64(len(hashBytes)),
		SHA256: hex.EncodeToString(h[:]),
	})

	err := am.Import(context.Background(), "malicious-zip", zipPath)
	if err == nil || !strings.Contains(err.Error(), "invalid zip entry name") {
		t.Fatalf("expected zip entry name error, got %v", err)
	}
}

func TestArtifacts_ImportZipBomb(t *testing.T) {
	dir := t.TempDir()
	am := NewAssetManager(dir, nil)

	zipPath := filepath.Join(dir, "bomb.zip")
	f, _ := os.Create(zipPath)
	zw := zip.NewWriter(f)

	w, _ := zw.Create("bomb.txt")
	zeros := make([]byte, 1024*1024)
	for i := 0; i < 20; i++ {
		w.Write(zeros)
	}
	zw.Close()
	f.Close()

	hashBytes, _ := os.ReadFile(zipPath)
	h := sha256.Sum256(hashBytes)

	am.registry = append(am.registry, Artifact{
		ID:     "bomb-zip",
		Format: "zip",
		Bytes:  int64(len(hashBytes)),
		SHA256: hex.EncodeToString(h[:]),
	})

	err := am.Import(context.Background(), "bomb-zip", zipPath)
	if err == nil || !strings.Contains(err.Error(), "high compression ratio") {
		t.Fatalf("expected high compression ratio error, got %v", err)
	}
}

func TestArtifacts_Cancel(t *testing.T) {
	dir := t.TempDir()
	am := NewAssetManager(dir, nil)

	data := []byte("GGUF_dummy_data")
	hash := sha256.Sum256(data)
	hashHex := hex.EncodeToString(hash[:])

	am.registry = append(am.registry, Artifact{
		ID:     "test-cancel",
		URL:    "http://fake.url/cancel.gguf",
		Bytes:  int64(len(data)),
		SHA256: hashHex,
		Format: "gguf",
	})

	origTransport := http.DefaultClient.Transport
	defer func() { http.DefaultClient.Transport = origTransport }()

	http.DefaultClient.Transport = &mockTransport{
		roundTripFunc: func(req *http.Request) (*http.Response, error) {
			<-req.Context().Done()
			return nil, req.Context().Err()
		},
	}

	errCh := make(chan error)
	go func() {
		errCh <- am.Download(context.Background(), "test-cancel")
	}()

	time.Sleep(50 * time.Millisecond)
	am.Cancel("test-cancel")

	err := <-errCh
	if err == nil {
		t.Fatal("expected error on cancel")
	}
}
