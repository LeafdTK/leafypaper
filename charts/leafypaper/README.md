# leafypaper Helm chart

A Helm chart that stands up a distributed Minecraft cluster on Kubernetes:

```
┌────────────────────────────────────────────────────┐
│   master (Deployment, 1 replica)                   │
│   - chunk-ownership table                          │
│   - hotspot scheduler (k8s-style scoring)          │
│   - built-in proxy → external LoadBalancer         │
└──────────────┬─────────────────────────────────────┘
               │ 35353 (cluster)
       ┌───────┴───────┐
   server-0         server-1     ...      (StatefulSet, N replicas)
   PVC: world+      PVC: world+
        logs+...         logs+...
   25565 → master proxy 25577 → external client
```

## Install

```bash
# Default: 2 game servers, hotspot scheduler live, LoadBalancer proxy.
helm install leafy oci://ghcr.io/leafdtk/charts/leafypaper --version 0.1.0

# Override defaults at install:
helm install leafy oci://ghcr.io/leafdtk/charts/leafypaper --version 0.1.0 \
  --set server.replicas=5 \
  --set master.hotspot.thresholdPlayers=60 \
  --set master.hotspot.dryRun=false
```

The chart creates the `leafypaper` namespace itself by default — no
`kubectl create ns` required.

## What's deployed

| Object | Kind | Purpose |
|---|---|---|
| `leafy-master` | Deployment (1 replica, Recreate strategy) | Master + built-in proxy |
| `master` | ClusterIP Service | Game-server-facing on 35353 |
| `proxy` | LoadBalancer Service | Minecraft-client-facing on 25577 |
| `leafy-server` | StatefulSet (default 2 replicas) | Game servers; pod ordinals = MultiPaper server names |
| `server` | Headless Service | Stable pod DNS for game servers |
| `data-leafy-server-N` | PVC (default 10Gi) | Per-pod world + logs + plugins |

## Configurable values

All settings live in [`values.yaml`](./values.yaml). Highlights:

- `image.{master,server}.{repository,tag,pullPolicy}` — tag is `latest` and
  pullPolicy is `Always` by default so a fresh image goes live on every pod
  restart. Bump these for pinned production deploys.
- `master.proxy.type` — `LoadBalancer` for cloud, `NodePort` or `ClusterIP`
  for bare-metal where you handle ingress yourself.
- `master.hotspot.*` — every JVM flag the scoring scheduler honors:
  - `thresholdPlayers` — players-in-region to trigger transfer (default 80)
  - `releaseThresholdPlayers` — drop level to release (default thresholdPlayers/2)
  - `releaseHoldSeconds` — hysteresis window (default 30)
  - `cooldownSeconds` — min seconds between transfers per region (default 60)
  - `dryRun` — log decisions but don't actually transfer (default `false`)
  - `crowdServers` — comma list of pod names to restrict the pool to.
    Empty = homogeneous mode (any connected pod is a candidate, picked by score).
- `server.persistence.size`, `server.persistence.storageClass` — PVC tuning.
- `server.replicas` — scale game servers up or down. The hotspot scheduler
  picks targets from whoever's connected, so you can scale at runtime.

## Send a console command to every server

The image bundles RCON enabled on port 25575 with password `leafd`:

```bash
for pod in $(kubectl -n leafypaper get pods -l app.kubernetes.io/component=server -o name); do
  kubectl -n leafypaper exec "$pod" -- \
    sh -c "echo 'time set day' | rcon-cli --host=127.0.0.1 --port=25575 --password=leafd"
done
```

## Where the images come from

The chart references images on ghcr.io that get built by the
[`.github/workflows/images.yml`](../../.github/workflows/images.yml) workflow
on every push to `main`. The chart itself is published by
[`.github/workflows/charts.yml`](../../.github/workflows/charts.yml) as an
OCI artifact on the same registry.

## Upgrade / uninstall

```bash
helm upgrade leafy oci://ghcr.io/leafdtk/charts/leafypaper --version 0.1.1
helm uninstall leafy --namespace leafypaper
```

PVCs are NOT deleted by `helm uninstall` — that's deliberate so worlds
survive an accidental teardown. Delete them manually if you actually want
to wipe state:

```bash
kubectl -n leafypaper delete pvc -l app.kubernetes.io/instance=leafy
```
