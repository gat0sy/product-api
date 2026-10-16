# product-api

[![CI/CD](https://github.com/gat0sy/product-api/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/gat0sy/product-api/actions/workflows/ci.yml)

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
├── ansible/                         # provisioning for both VMs (roles: common, minikube, ...)
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
| `test` | Installs `product-core` and runs the test suite; a failure stops the pipeline |
| `build` | Runs only if `test` passed; packages the jar (tests skipped) and uploads it as a workflow artifact |
| `docker` | On pushes to `develop` only: builds the image from the jar and pushes it to Docker Hub |

Required repository settings (**Settings → Secrets and variables → Actions**):

| Kind | Name | Value |
|---|---|---|
| Secret | `DOCKER_USERNAME` | Docker Hub username |
| Secret | `DOCKER_PASSWORD` | Docker Hub access token (Read & Write) |
| Variable | `DOCKER_IMAGE` | e.g. `gat0sy/product-api:latest` |

`.gitlab-ci.yml` is the original GitLab pipeline with the same three stages, using `DOCKER_USERNAME`, `DOCKER_PASSWORD` and `DOCKER_IMAGE` as CI/CD variables.

## Tests

```bash
./mvnw test
```

Tests use an in-memory H2 database (profile `test`, `src/test/resources/application-test.properties`), so no MySQL is needed. `src/test/resources/logback-test.xml` replaces the Loki appender with console logging.

| Test class | Scope |
|---|---|
| `ProductControllerTest` | Controller unit tests with a mocked `ProductService` (request mapping, parameters, JSON) |
| `ProductApiIntegrationTest` | Full context + H2: create/list/update flow, low-stock threshold (≤ 5), health probes, Prometheus endpoint |
| `ProductApiApplicationTests` | Application context loads |

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

### Hardware and resource allocation

Everything runs on a single physical machine:

| Host | |
|---|---|
| CPU | Intel Core i5-6400T, 4 cores / 4 threads @ 2.2 GHz |
| RAM | 7.7 GiB |
| OS | Debian 13 (trixie), kernel 6.12 |
| Hypervisor | VirtualBox 7.2.6 (KVM modules must be unloaded: `sudo modprobe -r kvm_intel kvm`) |

| VM | vCPU | RAM | Disk | OS | Runs |
|---|---|---|---|---|---|
| `k8s-node` | 2 | 4 GiB | 38 GB | Ubuntu 22.04.5 | Docker 29.3, minikube 1.38 (docker driver, Kubernetes 1.35), minikube container capped at 2800 MiB |
| `monitor-node` | 1 | 2 GiB | 11 GB | Ubuntu 22.04.5 | Docker 29.4, Prometheus + Loki 2.9 + Grafana |

The VMs take 3 of the host's 4 cores and 6 of its 7.7 GiB, so the host itself runs with little headroom. Other services on the host (MariaDB, PostgreSQL, LightDM, Jellyfin) are stopped while the lab runs.

Kubernetes requests and limits in `devops-l3gl`:

| Pod | CPU request / limit | Memory request / limit | Notes |
|---|---|---|---|
| `product-api` | 200m / 400m | 512Mi / 768Mi | Safety margins chosen while debugging startup failures: memory raised from 250Mi / 500Mi, JVM heap capped with `-Xms256m -Xmx400m` to stay within the limit, and probe delays extended from 30 s to 160 s (failure threshold 5) so a slow Spring Boot start is not killed before it finishes. |
| `mysql` | none | none | Not set yet (see limitations). |
| `wait-for-mysql` (init) | none | none | `busybox` sleep loop, negligible. |

Cluster-wide, requests total 1050m CPU (52%) and 772Mi memory (19%) of the node, most of it from the Kubernetes control plane and ingress-nginx.

Measured behavior with this allocation:

- MySQL's first-time initialization of an empty volume takes ~10 minutes; a restart on an existing volume takes a few seconds.
- During a `product-api` rolling update, the old and new Java pods run side by side, pushing the `k8s-node` load average above 6 on 2 vCPUs.
- The observability stack is light: Grafana ~100 MiB, Loki ~40 MiB, Prometheus ~17 MiB.

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

`k8s/mysql.yaml` defines:

- **PersistentVolumeClaim** `mysql-data` (2Gi, `standard` storage class) mounted at `/var/lib/mysql`, so data survives pod and VM restarts.
- **Deployment** `mysql` (MySQL 8.0) with `Recreate` strategy (never two MySQL pods on the same data directory) and a readiness probe (`mysqladmin ping` over TCP).
- **Service** `mysql` on port 3306.

`k8s/l3gl-devops.yaml` defines:

- **ConfigMap** `product-api-config`: datasource URL, username, and JPA `ddl-auto`.
- **Deployment** `product-api`: an init container that waits until the `mysql` Service accepts connections, image `gat0sy/product-api:latest`, password injected from `mysql-secret`, JVM heap capped at 400 MB (`-Xms256m -Xmx400m`), requests 200m CPU / 512Mi and limits 400m CPU / 768Mi, liveness and readiness probes on the actuator health endpoints (160 s initial delay, failure threshold 5).
- **Service** `product-api` on port 8086.
- **Ingress** routing `/` to the service, reachable at `http://192.168.56.102/`.

Deploying a new image after CI pushes it:

```bash
kubectl rollout restart deploy/product-api -n devops-l3gl
kubectl rollout status deploy/product-api -n devops-l3gl
```

### Boot automation

Both VMs bring their workloads back on boot, with no manual step:

- **`k8s-node`**: a `minikube` systemd service (template in `ansible/roles/minikube/templates/minikube.service.j2`) runs `minikube start` as `gatosy` once Docker is up, and `minikube stop` at shutdown. The workloads are already defined in the cluster, so Kubernetes restarts MySQL and product-api by itself; the init container keeps product-api waiting until MySQL is ready.
- **`monitor-node`**: Docker is enabled at boot and the Compose services use `restart: unless-stopped`, so Prometheus, Loki and Grafana come back on their own.

The service is installed by the Ansible `minikube` role. To inspect it on `k8s-node`:

```bash
systemctl status minikube.service     # after boot: active (exited)
journalctl -u minikube.service -b     # startup log
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

- **Resource allocation not tuned yet.** MySQL has no requests or limits, so it is scheduled as BestEffort and is the first pod evicted or OOM-killed under memory pressure. minikube's container is capped at 2800 MiB while the node advertises the full 4 GiB of the VM, so the scheduler can over-commit memory. `product-api` uses a rolling update, briefly running two JVMs on 2 vCPUs. `monitor-node`'s disk is 74% full.
- **Slow first start.** On the resource-constrained VM, MySQL's first-time initialization of an empty volume can take ~10 minutes. product-api waits for it (init container) instead of crash-looping, but the app is unavailable until then.
- **Hard-coded IPs.** The Loki URL (`logback-spring.xml`) and the Prometheus target (`prometheus.yml`) use fixed VM addresses.
- **Root database user.** The application connects to MySQL as `root`; a dedicated, least-privilege user would be safer.

## Authors

- **gat0sy**: infrastructure (CI/CD, Docker, Kubernetes, observability)
- **Mamadou**: initial API implementation and project setup
