# product-api

REST API for managing a product inventory, built with Spring Boot on top of the `product-core` library (entity, repository and service layer). The project ships with a full DevOps toolchain: CI/CD, a Docker image, Kubernetes manifests, and metrics/logs observability with Prometheus, Loki and Grafana.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 17 (Eclipse Temurin) |
| Framework | Spring Boot 3.5 (Web, Data JPA, Actuator) |
| Business layer | `product-core` 0.3.0 (bundled in `libs/`) |
| Database | MySQL 8.0 |
| CI/CD | GitHub Actions (`.github/workflows/ci.yml`), GitLab CI (`.gitlab-ci.yml`) |
| Container | Docker, image `gat0sy/product-api` on Docker Hub |
| Orchestration | Kubernetes (minikube), namespace `devops-l3gl` |
| Observability | Micrometer + Prometheus (metrics), Loki via loki4j (logs), Grafana (dashboards) |

## Repository layout

```
.
├── src/main/java/sn/isi/l3gl/api/   # Spring Boot app + ProductController
├── src/main/resources/
│   ├── application.properties       # server, datasource, actuator config
│   └── logback-spring.xml           # console + Loki log appenders
├── libs/product-core-0.3.0.jar      # business layer (entity/repository/service)
├── Dockerfile
├── k8s/
│   ├── l3gl-devops.yaml             # product-api ConfigMap, Deployment, Service, Ingress
│   └── mysql.yaml                   # MySQL Deployment + Service
├── observability/
│   ├── docker-compose.yml           # Prometheus, Loki, Grafana
│   └── prometheus.yml               # scrape config
├── .github/workflows/ci.yml         # GitHub Actions pipeline
└── .gitlab-ci.yml                   # original GitLab pipeline
```

## API

Base path: `/api/products` (port `8086`)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/products` | Create a product (JSON body) |
| `GET` | `/api/products` | List all products |
| `PUT` | `/api/products/{id}?quantity={n}` | Update a product's stock quantity |
| `GET` | `/api/products/low-stock/count` | Count products with quantity ≤ 5 |

Product model:

```json
{
  "id": 1,
  "name": "Keyboard",
  "description": "Mechanical keyboard",
  "price": 49.99,
  "quantity": 12
}
```

Examples:

```bash
curl -X POST http://localhost:8086/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Keyboard","description":"Mechanical keyboard","price":49.99,"quantity":12}'

curl http://localhost:8086/api/products
curl -X PUT 'http://localhost:8086/api/products/1?quantity=3'
curl http://localhost:8086/api/products/low-stock/count
```

### Actuator endpoints

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Overall health |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Metrics scraped by Prometheus |
| `/actuator/metrics`, `/actuator/info` | Metrics and app info |

## Running locally

Prerequisites: JDK 17 and a MySQL 8 instance with a `product_db` database.

```bash
# 1. Install the bundled product-core library into the local Maven repo
./mvnw install:install-file -Dfile=libs/product-core-0.3.0.jar \
  -DgroupId=sn.isi.l3gl.core -DartifactId=product-core -Dversion=0.3.0 -Dpackaging=jar

# 2. Build
./mvnw clean package -DskipTests

# 3. Run (override the datasource for a local MySQL)
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/product_db \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=<password> \
java -jar target/product-api-0.0.1-SNAPSHOT.jar
```

By default `application.properties` points at `jdbc:mysql://mysql:3306/product_db`, the in-cluster MySQL service.

### Docker

The Dockerfile packages a pre-built jar, so build it first:

```bash
./mvnw clean package -DskipTests
docker build -t gat0sy/product-api:latest .
docker run -p 8086:8086 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://<host>:3306/product_db \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=<password> \
  gat0sy/product-api:latest
```

## CI/CD

The GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every push and pull request:

| Job | What it does |
|---|---|
| `test` | Installs `product-core`, runs the Maven test phase (non-blocking) |
| `build` | Packages the jar and uploads it as a workflow artifact |
| `docker` | On pushes to `develop` only: builds the image from the jar and pushes it to Docker Hub |

Required repository settings (**Settings → Secrets and variables → Actions**):

| Kind | Name | Value |
|---|---|---|
| Secret | `DOCKER_USERNAME` | Docker Hub username |
| Secret | `DOCKER_PASSWORD` | Docker Hub access token (Read & Write) |
| Variable | `DOCKER_IMAGE` | e.g. `gat0sy/product-api:latest` |

`.gitlab-ci.yml` is the original GitLab pipeline with the same three stages, using `DOCKER_USERNAME`, `DOCKER_PASSWORD` and `DOCKER_IMAGE` as CI/CD variables.

## Infrastructure

Two VirtualBox VMs on a host-only network (`vboxnet0`):

| VM | IP | Role |
|---|---|---|
| `k8s-node` | `192.168.56.102` | minikube cluster running product-api and MySQL |
| `monitor-node` | `192.168.56.101` | Prometheus, Loki and Grafana via Docker Compose |

```
                    ┌──────────── k8s-node (192.168.56.102) ────────────┐
  HTTP :80 ───────► │ Ingress ──► product-api :8086 ──► mysql :3306     │
                    └───────────────┬───────────────────▲───────────────┘
                     logs (push)    │                   │ metrics (scrape)
                                    ▼                   │ /actuator/prometheus
                    ┌──────────── monitor-node (192.168.56.101) ────────┐
                    │ Loki :3100        Prometheus :9090                │
                    │           Grafana :3000                           │
                    └───────────────────────────────────────────────────┘
```

### Kubernetes deployment

Everything runs in the `devops-l3gl` namespace.

```bash
kubectl create namespace devops-l3gl

# The MySQL root password is never committed; create the secret on the cluster
kubectl create secret generic mysql-secret -n devops-l3gl \
  --from-literal=mysql-root-password='<password>'

kubectl apply -f k8s/mysql.yaml
kubectl apply -f k8s/l3gl-devops.yaml
```

`k8s/l3gl-devops.yaml` defines:

- **ConfigMap** `product-api-config`: datasource URL, username, and JPA `ddl-auto`.
- **Deployment** `product-api`: image `gat0sy/product-api:latest`, password injected from `mysql-secret`, JVM heap capped at 400 MB (`-Xms256m -Xmx400m`), requests 200m CPU / 512Mi and limits 400m CPU / 768Mi, liveness and readiness probes on the actuator health endpoints (160 s initial delay, failure threshold 5).
- **Service** `product-api` on port 8086.
- **Ingress** routing `/` to the service, reachable at `http://192.168.56.102/`.

Deploying a new image after CI pushes it:

```bash
kubectl rollout restart deploy/product-api -n devops-l3gl
kubectl rollout status deploy/product-api -n devops-l3gl
```

### Observability

On `monitor-node`:

```bash
cd observability
docker compose up -d
```

| Service | URL | Notes |
|---|---|---|
| Prometheus | `http://192.168.56.101:9090` | Scrapes `192.168.56.102:80/actuator/prometheus` every 10 s |
| Loki | `http://192.168.56.101:3100` | Receives logs pushed by the app |
| Grafana | `http://192.168.56.101:3000` | Add Prometheus and Loki as data sources |

Logs are pushed by the loki4j Logback appender with labels `app=product-api` and `level=<LEVEL>`, in batches of 10 entries or every 5 s. They are also written to the console, so `kubectl logs` still works.

## Known limitations

These are known and planned to be addressed shortly:

- **No persistent storage for MySQL.** `k8s/mysql.yaml` mounts no volume, so the database is reinitialized whenever the MySQL pod or the VM restarts. A PersistentVolumeClaim on the cluster's default `standard` storage class is planned.
- **No readiness probe on MySQL.** The MySQL pod reports Ready before it accepts connections. On the resource-constrained VM, initialization can take several minutes, during which product-api fails to start (`Unable to determine Dialect without JDBC metadata`) and goes into `CrashLoopBackOff` until MySQL is up.
- **Tests are skipped in CI.** The `test` job runs `mvn test -DskipTests` and is allowed to fail, so it does not actually validate anything yet.
- **Hard-coded IPs.** The Loki URL (`logback-spring.xml`) and the Prometheus target (`prometheus.yml`) use fixed VM addresses.
- **Root database user.** The application connects to MySQL as `root`; a dedicated, least-privilege user would be safer.

## Authors

- **gat0sy**: infrastructure (CI/CD, Docker, Kubernetes, observability)
- **Mamadou**: initial API implementation and project setup
