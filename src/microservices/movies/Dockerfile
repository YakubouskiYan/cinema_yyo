FROM golang:1.23-alpine AS builder

WORKDIR /app

# Copy go mod and sum files
COPY go.mod ./

# Download all dependencies
RUN go mod download

# Copy the source code
COPY . .

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o movies-service .

# Use a smaller image for the final application
FROM gcr.io/distroless/static-debian12

WORKDIR /app

COPY --from=builder /app/movies-service .

EXPOSE 8081

CMD ["/app/movies-service"]