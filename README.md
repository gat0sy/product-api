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
├── ansible/                         # provisions both VMs (see "Provisioning with Ansible")
│   ├── site.yml, inventory.ini, ansible.cfg
│   ├── group_vars/all/              # shared variables, env.yml.example, secrets.yml.example
│   └── roles/                       # common, minikube, k8s_app, ingress_proxy, observability
├── observability/
│   ├── docker-compose.yml           # Prometheus, Loki, Grafana (pinned, named volumes)
│   └── grafana/                     # provisioned datasources and dashboard
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

#### Sizing recommendations

The current machine is used as the **minimal** profile: everything works on it, but first starts are slow and the host has almost no headroom. The **recommended** profile is what to aim for when reusing this project; it is sized from what was observed on the minimal one, not tested.

| | Minimal (tested) | Recommended |
|---|---|---|
| Host | 4 cores, 8 GiB RAM | 6–8 cores, 16 GiB RAM |
| `k8s-node` | 2 vCPU, 4 GiB, 38 GB disk | 4 vCPU, 8 GiB, 40 GB disk |
| minikube (`minikube_cpus`, `minikube_memory_mb`) | 2 CPUs, 2800 MB | 4 CPUs, 6144 MB |
| `monitor-node` | 1 vCPU, 2 GiB, 11 GB disk | 2 vCPU, 4 GiB, 30 GB disk |

Why these numbers:

- **`k8s-node` cannot go below the minimal profile.** minikube refuses to start with fewer than 2 CPUs, and it is given 2800 MB, so a 4 GiB VM is the practical floor.
- **CPU is the main bottleneck.** The Kubernetes control plane and ingress already request half of 2 vCPUs, and a `product-api` rolling update runs two JVMs side by side (load average above 6). With 4 vCPUs, MySQL's ~10-minute first initialization and rollouts should be much faster.
- **Memory headroom for the cluster.** 6 GB for minikube fits MySQL, two JVMs during a rollout, the control plane and ingress without memory pressure.
- **`monitor-node` disk fills up.** 11 GB is already over 70% used. Loki keeps logs indefinitely (see Known limitations) and Prometheus keeps 15 days of metrics. 2 vCPUs also shorten Grafana's ~10-minute first start.

The minikube values only apply when the cluster is first created (`ansible/roles/minikube/defaults/main.yml`); an existing cluster keeps its size until it is deleted and recreated.

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

### Provisioning with Ansible

`ansible/` configures both VMs from a fresh Ubuntu 22.04 install. Running it against already configured VMs changes nothing (`changed=0`), so it is safe to re-run.

**Manual prerequisites** (done once per VM, not automated):

- Ubuntu 22.04 VMs sized at least to the minimal profile in "Sizing recommendations" (the recommended profile is better).
- A user that can `sudo` on each VM (`devops` by default, set as `app_user` in `env.yml`).
- `enp0s3`: NAT adapter with DHCP (internet access).
- `enp0s8`: host-only adapter (`vboxnet0`) with a static IP in netplan. The defaults are `192.168.56.102` for `k8s-node` and `192.168.56.101` for `monitor-node`; other addresses work if you set them in `env.yml`.
- A dedicated SSH key from the control machine:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ansible_ed25519 -N "" -C "ansible@client"
ssh-copy-id -i ~/.ssh/ansible_ed25519.pub devops@192.168.56.102
ssh-copy-id -i ~/.ssh/ansible_ed25519.pub devops@192.168.56.101
```

**Running it** (from `ansible/`, on a machine with Ansible ≥ 2.15):

```bash
ansible-galaxy collection install -r requirements.yml
cp group_vars/all/env.yml.example group_vars/all/env.yml           # then set your VM user and IPs
cp group_vars/all/secrets.yml.example group_vars/all/secrets.yml   # then choose your own passwords
ansible all -m ping
ansible-playbook site.yml --ask-become-pass --check --diff          # dry run
ansible-playbook site.yml --ask-become-pass
```

**Settings to fill in** (both files are git-ignored):

| File | Variable | Default in `.example` | Used for |
|---|---|---|---|
| `env.yml` | `app_user` | `devops` | SSH login user on both VMs (needs `sudo`); owns Docker, minikube and `~/observability` |
| `env.yml` | `k8s_node_ip` | `192.168.56.102` | Inventory address of `k8s-node`; Prometheus scrape target |
| `env.yml` | `monitor_node_ip` | `192.168.56.101` | Inventory address of `monitor-node`; the app's `LOKI_URL` (`monitoring-config` ConfigMap) |
| `secrets.yml` | `mysql_root_password` | `change-me` (rejected) | MySQL root password (`mysql-secret`) |
| `secrets.yml` | `grafana_admin_password` | `change-me` (rejected) | Grafana `admin` password (`~/observability/.env`) |

`env.yml` is the only place the VM user and IPs are set; the playbook stops before connecting if it is missing. The values in `secrets.yml` are only used when MySQL and Grafana are first created; changing them later does not change the passwords stored in existing data volumes.

| Role | Hosts | What it sets up |
|---|---|---|
| `common` | both | Docker Engine and Compose plugin from Docker's apt repository, Docker enabled at boot, `app_user` in the `docker` group |
| `minikube` | `k8s-node` | minikube 1.38.1 and kubectl 1.35.3 (checksum-verified), the `minikube` systemd service (creates the cluster on first start: docker driver, 2 CPUs, 2800 MB, Kubernetes 1.35.1), ingress and storage addons |
| `k8s_app` | `k8s-node` | `devops-l3gl` namespace, `mysql-secret` from `secrets.yml`, `monitoring-config` (`LOKI_URL`, from `monitor_node_ip`), then `k8s/mysql.yaml` and `k8s/l3gl-devops.yaml` |
| `ingress_proxy` | `k8s-node` | host nginx forwarding port 80 (`/api/`, `/actuator/`) to the minikube ingress at `minikube ip`, so the API is reachable from outside the VM |
| `observability` | `monitor-node` | copies `observability/` to `~/observability`, writes `prometheus.yml` (scrape target from `k8s_node_ip`) and the Grafana admin password to `.env`, runs `docker compose up -d` |

On a fresh VM, expect the first run to take a while: creating the minikube cluster, MySQL's first initialization and Grafana's first start can each take around 10 minutes on this hardware.

### Kubernetes deployment

Everything runs in the `devops-l3gl` namespace. The `k8s_app` role applies it; the manual equivalent is:

```bash
kubectl create namespace devops-l3gl

# The MySQL root password is never committed; create the secret on the cluster
kubectl create secret generic mysql-secret -n devops-l3gl \
  --from-literal=mysql-root-password='<password>'

# Where the app pushes its logs (optional; without it logs only go to the console)
kubectl create configmap monitoring-config -n devops-l3gl \
  --from-literal=LOKI_URL=http://192.168.56.101:3100/loki/api/v1/push

kubectl apply -f k8s/mysql.yaml
kubectl apply -f k8s/l3gl-devops.yaml
```

`k8s/mysql.yaml` defines:

- **PersistentVolumeClaim** `mysql-data` (2Gi, `standard` storage class) mounted at `/var/lib/mysql`, so data survives pod and VM restarts.
- **Deployment** `mysql` (MySQL 8.0) with `Recreate` strategy (never two MySQL pods on the same data directory) and a readiness probe (`mysqladmin ping` over TCP).
- **Service** `mysql` on port 3306.

`k8s/l3gl-devops.yaml` defines:

- **ConfigMap** `product-api-config`: datasource URL, username, and JPA `ddl-auto`.
- **Deployment** `product-api`: an init container that waits until the `mysql` Service accepts connections, image `gat0sy/product-api:latest`, password injected from `mysql-secret`, `LOKI_URL` from the optional `monitoring-config`, JVM heap capped at 400 MB (`-Xms256m -Xmx400m`), requests 200m CPU / 512Mi and limits 400m CPU / 768Mi, liveness and readiness probes on the actuator health endpoints (160 s initial delay, failure threshold 5).
- **Service** `product-api` on port 8086.
- **Ingress** routing `/` to the service, reachable at `http://192.168.56.102/`.

Deploying a new image after CI pushes it:

```bash
kubectl rollout restart deploy/product-api -n devops-l3gl
kubectl rollout status deploy/product-api -n devops-l3gl
```

### Boot automation

Both VMs bring their workloads back on boot, with no manual step:

- **`k8s-node`**: a `minikube` systemd service (template in `ansible/roles/minikube/templates/minikube.service.j2`) runs `minikube start` as `app_user` once Docker is up, and `minikube stop` at shutdown. The workloads are already defined in the cluster, so Kubernetes restarts MySQL and product-api by itself; the init container keeps product-api waiting until MySQL is ready.
- **`monitor-node`**: Docker is enabled at boot and the Compose services use `restart: unless-stopped`, so Prometheus, Loki and Grafana come back on their own.

The service is installed by the Ansible `minikube` role. To inspect it on `k8s-node`:

```bash
systemctl status minikube.service     # after boot: active (exited)
journalctl -u minikube.service -b     # startup log
```

### Observability

On `monitor-node`:

The stack is deployed by the Ansible `observability` role, which copies `observability/` to `~/observability`, renders `prometheus.yml` from `ansible/roles/observability/templates/prometheus.yml.j2` and runs `docker compose up -d`. Images are pinned (Prometheus 3.11.1, Loki 2.9.0, Grafana 12.4.2) and each service keeps its data in a named volume. The Grafana admin password comes from a `.env` file written by Ansible.

| Service | URL | Notes |
|---|---|---|
| Prometheus | `http://192.168.56.101:9090` | Scrapes `192.168.56.102:80/actuator/prometheus` every 10 s |
| Loki | `http://192.168.56.101:3100` | Receives logs pushed by the app |
| Grafana | `http://192.168.56.101:3000` | Data sources and the "Product API Monitoring" dashboard are provisioned from `observability/grafana/` |

Logs are pushed to `LOKI_URL` (default `http://localhost:3100/loki/api/v1/push`) by the loki4j Logback appender with labels `app=product-api` and `level=<LEVEL>`, in batches of 10 entries or every 5 s. They are also written to the console, so `kubectl logs` still works.

## Known limitations

These are known and planned to be addressed shortly:

- **Resource allocation not tuned yet.** MySQL has no requests or limits, so it is scheduled as BestEffort and is the first pod evicted or OOM-killed under memory pressure. minikube's container is capped at 2800 MiB while the node advertises the full 4 GiB of the VM, so the scheduler can over-commit memory. `product-api` uses a rolling update, briefly running two JVMs on 2 vCPUs. `monitor-node`'s disk is 74% full.
- **Slow first start.** On the resource-constrained VMs, MySQL's first-time initialization of an empty volume and Grafana's first start on an empty database each take ~10 minutes. product-api waits for MySQL (init container) instead of crash-looping, but the app is unavailable until then. Later restarts reuse the volumes and are much faster.
- **No Loki retention.** Loki currently uses filesystem-backed storage without an explicit retention policy (the image's default config, no compactor), so its disk consumption grows over time. Prometheus keeps its default 15 days of metrics. The recommended monitoring VM size provides additional capacity but does not replace retention configuration and disk monitoring.
- **Root database user.** The application connects to MySQL as `root`; a dedicated, least-privilege user would be safer.

## Authors

- **gat0sy**: infrastructure (CI/CD, Docker, Kubernetes, observability)
- **Mamadou**: initial API implementation and project setup
