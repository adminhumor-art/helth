FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/sladkaya-api ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/sladkaya-api /sladkaya-api
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/sladkaya-api"]
