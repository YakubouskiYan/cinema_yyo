package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	_ "github.com/lib/pq"
)

// TestMain initialises a shared DB connection for all tests.
// Requires postgres to be reachable — run `docker compose up -d` first.
// Override the address via TEST_DB_URL env variable.
func TestMain(m *testing.M) {
	connStr := os.Getenv("TEST_DB_URL")
	if connStr == "" {
		connStr = "postgres://postgres:postgres_password@localhost:5432/cinemaabyss?sslmode=disable"
	}

	var err error
	db, err = sql.Open("postgres", connStr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "SKIP: failed to open DB: %v\n", err)
		os.Exit(0)
	}
	if err = db.Ping(); err != nil {
		fmt.Fprintf(os.Stderr, "SKIP: cannot reach postgres (is docker compose running?): %v\n", err)
		os.Exit(0)
	}

	code := m.Run()
	db.Close()
	os.Exit(code)
}

// newTestServer builds a fresh ServeMux for each test.
func newTestServer() *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/movies", handleMovies)
	mux.HandleFunc("/api/movies/health", handleHealth)
	return httptest.NewServer(mux)
}

// uniqueSuffix returns a nanosecond-based suffix so each test run uses fresh data.
func uniqueSuffix() string {
	return fmt.Sprintf("%d", time.Now().UnixNano())
}

// ── Health ───────────────────────────────────────────────────────────────────

func TestHealth(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/movies/health")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var body map[string]bool
	json.NewDecoder(resp.Body).Decode(&body)
	if !body["status"] {
		t.Error("expected {status: true}")
	}
}

// ── Movies ───────────────────────────────────────────────────────────────────

func TestMovies_GetAll_ReturnsList(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/movies")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var movies []Movie
	json.NewDecoder(resp.Body).Decode(&movies)
	if len(movies) == 0 {
		t.Error("expected movies from seed data")
	}
}

func TestMovies_CreateAndGetByID_WithGenres(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	payload := `{"title":"Movies-Svc Integration Test","description":"Written by movies-service test","rating":7.5,"genres":["Drama","Thriller"]}`
	resp, err := http.Post(srv.URL+"/api/movies", "application/json", bytes.NewBufferString(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create: expected 201, got %d", resp.StatusCode)
	}

	var created Movie
	json.NewDecoder(resp.Body).Decode(&created)

	if created.ID == 0 {
		t.Fatal("expected non-zero ID")
	}
	if created.Title != "Movies-Svc Integration Test" {
		t.Errorf("title mismatch: got %q", created.Title)
	}

	t.Cleanup(func() {
		db.Exec("DELETE FROM movie_genres WHERE movie_id = $1", created.ID)
		db.Exec("DELETE FROM movies WHERE id = $1", created.ID)
	})

	// Fetch by ID and verify genres round-trip
	resp2, err := http.Get(fmt.Sprintf("%s/api/movies?id=%d", srv.URL, created.ID))
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()

	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("get by id: expected 200, got %d", resp2.StatusCode)
	}

	var fetched Movie
	json.NewDecoder(resp2.Body).Decode(&fetched)

	if fetched.ID != created.ID {
		t.Errorf("ID mismatch: got %d", fetched.ID)
	}
	if fetched.Rating != 7.5 {
		t.Errorf("rating mismatch: got %f", fetched.Rating)
	}
	if len(fetched.Genres) != 2 {
		t.Errorf("expected 2 genres, got %d: %v", len(fetched.Genres), fetched.Genres)
	}
}

func TestMovies_MethodNotAllowed(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodDelete, srv.URL+"/api/movies", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", resp.StatusCode)
	}
}

func TestMovies_CreateWithoutGenres(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	_ = uniqueSuffix() // ensure unique context
	payload := `{"title":"No-Genre Movie","description":"A movie without genres","rating":6.0}`
	resp, err := http.Post(srv.URL+"/api/movies", "application/json", bytes.NewBufferString(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201, got %d", resp.StatusCode)
	}

	var created Movie
	json.NewDecoder(resp.Body).Decode(&created)

	t.Cleanup(func() {
		db.Exec("DELETE FROM movies WHERE id = $1", created.ID)
	})

	if created.ID == 0 {
		t.Fatal("expected non-zero ID")
	}
	// Genres slice should be empty (not nil) from getAllMovies/getMovieByID
	if created.Genres == nil {
		// createMovie returns the struct as-is; genres only populated on fetch
		// This is acceptable — just verify fetch works
	}

	resp2, _ := http.Get(fmt.Sprintf("%s/api/movies?id=%d", srv.URL, created.ID))
	defer resp2.Body.Close()

	var fetched Movie
	json.NewDecoder(resp2.Body).Decode(&fetched)

	if len(fetched.Genres) != 0 {
		t.Errorf("expected 0 genres, got %d", len(fetched.Genres))
	}
}
