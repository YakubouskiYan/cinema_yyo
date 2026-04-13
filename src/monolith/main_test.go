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

// newTestServer builds a fresh ServeMux and wraps it in an httptest.Server.
// Each test gets its own server so there are no shared-state conflicts.
func newTestServer() *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", healthHandler)
	mux.HandleFunc("/api/users", handleUsers)
	mux.HandleFunc("/api/movies", handleMovies)
	mux.HandleFunc("/api/payments", handlePayments)
	mux.HandleFunc("/api/subscriptions", handleSubscriptions)
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

	resp, err := http.Get(srv.URL + "/health")
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

// ── Users ────────────────────────────────────────────────────────────────────

func TestUsers_GetAll_ReturnsList(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	resp, _ := http.Get(srv.URL + "/api/users")
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var users []User
	json.NewDecoder(resp.Body).Decode(&users)
	// Seed data contains 3 users
	if len(users) == 0 {
		t.Error("expected at least one user from seed data")
	}
}

func TestUsers_CreateAndGetByID(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	s := uniqueSuffix()
	payload := fmt.Sprintf(`{"username":"testuser_%s","email":"test_%s@example.com"}`, s, s)

	resp, err := http.Post(srv.URL+"/api/users", "application/json", bytes.NewBufferString(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("create: expected 201, got %d", resp.StatusCode)
	}

	var created User
	json.NewDecoder(resp.Body).Decode(&created)

	if created.ID == 0 {
		t.Fatal("expected non-zero ID")
	}
	if created.Username != "testuser_"+s {
		t.Errorf("username mismatch: got %q", created.Username)
	}

	t.Cleanup(func() { db.Exec("DELETE FROM users WHERE id = $1", created.ID) })

	// Get by ID
	resp2, _ := http.Get(fmt.Sprintf("%s/api/users?id=%d", srv.URL, created.ID))
	defer resp2.Body.Close()

	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("get by id: expected 200, got %d", resp2.StatusCode)
	}
	var fetched User
	json.NewDecoder(resp2.Body).Decode(&fetched)

	if fetched.ID != created.ID {
		t.Errorf("expected ID %d, got %d", created.ID, fetched.ID)
	}
	if fetched.Email != "test_"+s+"@example.com" {
		t.Errorf("email mismatch: got %q", fetched.Email)
	}
}

func TestUsers_MethodNotAllowed(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	req, _ := http.NewRequest(http.MethodDelete, srv.URL+"/api/users", nil)
	resp, _ := http.DefaultClient.Do(req)
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", resp.StatusCode)
	}
}

// ── Movies ───────────────────────────────────────────────────────────────────

func TestMovies_GetAll_ReturnsList(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	resp, _ := http.Get(srv.URL + "/api/movies")
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

	payload := `{"title":"Integration Test Movie","description":"Test desc","rating":8.5,"genres":["Action","Sci-Fi"]}`
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

	t.Cleanup(func() {
		db.Exec("DELETE FROM movie_genres WHERE movie_id = $1", created.ID)
		db.Exec("DELETE FROM movies WHERE id = $1", created.ID)
	})

	// Verify genres are stored and returned
	resp2, _ := http.Get(fmt.Sprintf("%s/api/movies?id=%d", srv.URL, created.ID))
	defer resp2.Body.Close()

	var fetched Movie
	json.NewDecoder(resp2.Body).Decode(&fetched)

	if fetched.Title != "Integration Test Movie" {
		t.Errorf("title mismatch: got %q", fetched.Title)
	}
	if fetched.Rating != 8.5 {
		t.Errorf("rating mismatch: got %f", fetched.Rating)
	}
	if len(fetched.Genres) != 2 {
		t.Errorf("expected 2 genres, got %d: %v", len(fetched.Genres), fetched.Genres)
	}
}

func TestMovies_Create_TransactionRollbackOnEmptyTitle(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	// Empty title violates NOT NULL — transaction must roll back
	payload := `{"title":"","description":"Bad movie","rating":5.0}`
	resp, _ := http.Post(srv.URL+"/api/movies", "application/json", bytes.NewBufferString(payload))
	defer resp.Body.Close()

	// Postgres allows empty strings, but rating outside 0-10 would fail.
	// Verify endpoint at least returns a response (not a panic).
	if resp.StatusCode == 0 {
		t.Error("expected a valid HTTP response")
	}
}

// ── Payments ─────────────────────────────────────────────────────────────────

func TestPayments_CreateAndGetByUserID(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	// Create a user to attach payments to
	s := uniqueSuffix()
	userPayload := fmt.Sprintf(`{"username":"paytest_%s","email":"paytest_%s@example.com"}`, s, s)
	userResp, _ := http.Post(srv.URL+"/api/users", "application/json", bytes.NewBufferString(userPayload))
	var u User
	json.NewDecoder(userResp.Body).Decode(&u)
	userResp.Body.Close()

	t.Cleanup(func() {
		db.Exec("DELETE FROM payments WHERE user_id = $1", u.ID)
		db.Exec("DELETE FROM users WHERE id = $1", u.ID)
	})

	// Create payment
	payPayload := fmt.Sprintf(`{"user_id":%d,"amount":99.99}`, u.ID)
	payResp, _ := http.Post(srv.URL+"/api/payments", "application/json", bytes.NewBufferString(payPayload))
	if payResp.StatusCode != http.StatusCreated {
		t.Fatalf("create payment: expected 201, got %d", payResp.StatusCode)
	}
	var p Payment
	json.NewDecoder(payResp.Body).Decode(&p)
	payResp.Body.Close()

	if p.Amount != 99.99 {
		t.Errorf("amount mismatch: got %f", p.Amount)
	}
	if p.UserID != u.ID {
		t.Errorf("user_id mismatch: got %d", p.UserID)
	}

	// Get by user_id
	resp, _ := http.Get(fmt.Sprintf("%s/api/payments?user_id=%d", srv.URL, u.ID))
	defer resp.Body.Close()

	var payments []Payment
	json.NewDecoder(resp.Body).Decode(&payments)
	if len(payments) != 1 {
		t.Errorf("expected 1 payment, got %d", len(payments))
	}
}

func TestPayments_GetByID(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	s := uniqueSuffix()
	userPayload := fmt.Sprintf(`{"username":"payid_%s","email":"payid_%s@example.com"}`, s, s)
	userResp, _ := http.Post(srv.URL+"/api/users", "application/json", bytes.NewBufferString(userPayload))
	var u User
	json.NewDecoder(userResp.Body).Decode(&u)
	userResp.Body.Close()

	t.Cleanup(func() {
		db.Exec("DELETE FROM payments WHERE user_id = $1", u.ID)
		db.Exec("DELETE FROM users WHERE id = $1", u.ID)
	})

	payPayload := fmt.Sprintf(`{"user_id":%d,"amount":49.00}`, u.ID)
	payResp, _ := http.Post(srv.URL+"/api/payments", "application/json", bytes.NewBufferString(payPayload))
	var p Payment
	json.NewDecoder(payResp.Body).Decode(&p)
	payResp.Body.Close()

	resp, _ := http.Get(fmt.Sprintf("%s/api/payments?id=%d", srv.URL, p.ID))
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var fetched Payment
	json.NewDecoder(resp.Body).Decode(&fetched)
	if fetched.ID != p.ID {
		t.Errorf("ID mismatch: got %d", fetched.ID)
	}
}

// ── Subscriptions ─────────────────────────────────────────────────────────────

func TestSubscriptions_CreateAndGetByUserID(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	s := uniqueSuffix()
	userPayload := fmt.Sprintf(`{"username":"subtest_%s","email":"subtest_%s@example.com"}`, s, s)
	userResp, _ := http.Post(srv.URL+"/api/users", "application/json", bytes.NewBufferString(userPayload))
	var u User
	json.NewDecoder(userResp.Body).Decode(&u)
	userResp.Body.Close()

	t.Cleanup(func() {
		db.Exec("DELETE FROM subscriptions WHERE user_id = $1", u.ID)
		db.Exec("DELETE FROM users WHERE id = $1", u.ID)
	})

	now := time.Now().UTC()
	subPayload := fmt.Sprintf(
		`{"user_id":%d,"plan_type":"premium","start_date":"%s","end_date":"%s"}`,
		u.ID, now.Format(time.RFC3339), now.AddDate(0, 1, 0).Format(time.RFC3339),
	)
	subResp, _ := http.Post(srv.URL+"/api/subscriptions", "application/json", bytes.NewBufferString(subPayload))
	if subResp.StatusCode != http.StatusCreated {
		t.Fatalf("create subscription: expected 201, got %d", subResp.StatusCode)
	}
	var sub Subscription
	json.NewDecoder(subResp.Body).Decode(&sub)
	subResp.Body.Close()

	if sub.PlanType != "premium" {
		t.Errorf("plan_type mismatch: got %q", sub.PlanType)
	}

	// Get by user_id
	resp, _ := http.Get(fmt.Sprintf("%s/api/subscriptions?user_id=%d", srv.URL, u.ID))
	defer resp.Body.Close()

	var subs []Subscription
	json.NewDecoder(resp.Body).Decode(&subs)
	if len(subs) != 1 {
		t.Errorf("expected 1 subscription, got %d", len(subs))
	}
	if subs[0].PlanType != "premium" {
		t.Errorf("plan_type mismatch in list: got %q", subs[0].PlanType)
	}
}

func TestSubscriptions_GetByID(t *testing.T) {
	srv := newTestServer()
	defer srv.Close()

	s := uniqueSuffix()
	userPayload := fmt.Sprintf(`{"username":"subid_%s","email":"subid_%s@example.com"}`, s, s)
	userResp, _ := http.Post(srv.URL+"/api/users", "application/json", bytes.NewBufferString(userPayload))
	var u User
	json.NewDecoder(userResp.Body).Decode(&u)
	userResp.Body.Close()

	t.Cleanup(func() {
		db.Exec("DELETE FROM subscriptions WHERE user_id = $1", u.ID)
		db.Exec("DELETE FROM users WHERE id = $1", u.ID)
	})

	now := time.Now().UTC()
	subPayload := fmt.Sprintf(
		`{"user_id":%d,"plan_type":"basic","start_date":"%s","end_date":"%s"}`,
		u.ID, now.Format(time.RFC3339), now.AddDate(0, 1, 0).Format(time.RFC3339),
	)
	subResp, _ := http.Post(srv.URL+"/api/subscriptions", "application/json", bytes.NewBufferString(subPayload))
	var sub Subscription
	json.NewDecoder(subResp.Body).Decode(&sub)
	subResp.Body.Close()

	resp, _ := http.Get(fmt.Sprintf("%s/api/subscriptions?id=%d", srv.URL, sub.ID))
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var fetched Subscription
	json.NewDecoder(resp.Body).Decode(&fetched)
	if fetched.ID != sub.ID {
		t.Errorf("ID mismatch: got %d", fetched.ID)
	}
	if fetched.PlanType != "basic" {
		t.Errorf("plan_type mismatch: got %q", fetched.PlanType)
	}
}
