#!/bin/sh
# One-shot bucket setup for recordings (ADR-003): private bucket with versioning.
set -e
# Bitnami's entrypoint runs MinIO once in the background for setup and then restarts it, so the healthcheck
# can pass just before a short outage: retry instead of failing on the first refused connection.
i=0
until mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"; do
  i=$((i + 1)); [ "$i" -lt 30 ] || exit 1
  sleep 2
done
mc mb --ignore-existing local/medconnect-recordings
mc version enable local/medconnect-recordings
mc anonymous set none local/medconnect-recordings
