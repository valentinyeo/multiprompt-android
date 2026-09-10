#!/usr/bin/env python3
"""Generate the stable fixture vectors for multiprompt sync protocol v1.

Implements exactly what docs/sync-protocol-v1.md specifies, using argon2-cffi
(hash_secret_raw) and cryptography (AESGCM), and writes canonical JSON vectors that
both the Kotlin tests (app/src/test/resources/sync/) and, later, the zigshell Zig
verifier must reproduce byte-for-byte.

The committed fixture file pins one set of random values. Re-running this script
generates NEW vectors; that changes the committed file and is a deliberate,
reviewable act — do not do it casually.

Usage:
    pip install argon2-cffi cryptography
    python3 scripts/gen-sync-fixtures.py > app/src/test/resources/sync/sync-protocol-v1-fixtures.json
"""

import base64
import hashlib
import json
import os
import secrets
import sys

from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PROTOCOL = "multiprompt-sync-v1"
KDF = {"memoryKiB": 65536, "iterations": 3, "parallelism": 1}
ARGON2_VERSION = 19  # 0x13
KEK_BYTES = 32
VAULT_KEY_BYTES = 32
IV_BYTES = 12
SALT_BYTES = 16


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def canonical_kdf(salt: bytes) -> str:
    return (
        '{"alg":"argon2id"'
        f',"version":{ARGON2_VERSION}'
        f',"memoryKiB":{KDF["memoryKiB"]}'
        f',"iterations":{KDF["iterations"]}'
        f',"parallelism":{KDF["parallelism"]}'
        f',"salt":"{b64(salt)}"}}'
    )


def canonical_envelope(salt, wrap_iv, wrap_ct, sealed_records, record_ivs):
    parts = []
    for record_id in sorted(sealed_records):
        parts.append(
            f'"{record_id}":{{"iv":"{b64(record_ivs[record_id])}"'
            f',"ct":"{b64(sealed_records[record_id])}"}}'
        )
    return (
        '{"v":1'
        f',"kdf":{canonical_kdf(salt)}'
        f',"wrap":{{"alg":"AES-256-GCM","iv":"{b64(wrap_iv)}","ct":"{b64(wrap_ct)}"}}'
        f',"records":{{{",".join(parts)}}}}}'
    )


def build_vector(name, passphrase, records_plaintexts):
    salt = secrets.token_bytes(SALT_BYTES)
    vault_key = secrets.token_bytes(VAULT_KEY_BYTES)
    wrap_iv = secrets.token_bytes(IV_BYTES)
    record_ivs = {record_id: secrets.token_bytes(IV_BYTES) for record_id in records_plaintexts}

    kek = hash_secret_raw(
        secret=passphrase.encode("utf-8"),
        salt=salt,
        time_cost=KDF["iterations"],
        memory_cost=KDF["memoryKiB"],
        parallelism=KDF["parallelism"],
        hash_len=KEK_BYTES,
        type=Type.ID,
        version=ARGON2_VERSION,
    )
    wrap_aad = f"mp-sync-v1/wrap/{canonical_kdf(salt)}".encode("utf-8")
    wrap_ct = AESGCM(kek).encrypt(wrap_iv, vault_key, wrap_aad)

    sealed = {}
    for record_id, plaintext in records_plaintexts.items():
        aad = f"mp-sync-v1/record/{record_id}".encode("utf-8")
        sealed[record_id] = AESGCM(vault_key).encrypt(record_ivs[record_id], plaintext.encode("utf-8"), aad)

    envelope = canonical_envelope(salt, wrap_iv, wrap_ct, sealed, record_ivs)

    return {
        "name": name,
        "passphrase": passphrase,
        "salt": b64(salt),
        "vaultKey": b64(vault_key),
        "wrapIv": b64(wrap_iv),
        "kdf": dict(KDF),
        "records": [
            {
                "id": rid,
                "iv": b64(record_ivs[rid]),
                "plaintextUtf8": records_plaintexts[rid],
                "ct": b64(sealed[rid]),
            }
            for rid in records_plaintexts
        ],
        "kek": b64(kek),
        "wrapCt": b64(wrap_ct),
        "envelopeJson": envelope,
        "envelopeSha256": hashlib.sha256(envelope.encode("utf-8")).hexdigest(),
    }


def build_entity_vector(name, vault_key, record_id, entity_id, plaintext):
    iv = secrets.token_bytes(IV_BYTES)
    account_id = "acc-test"
    revision = 1
    schema_version = 1
    aad = (
        f"mp-sync-v1/entity/{record_id}/{entity_id}"
        f"/{account_id}/{revision}/{schema_version}"
    ).encode("utf-8")
    ct = AESGCM(vault_key).encrypt(iv, plaintext.encode("utf-8"), aad)
    payload = (
        '{"v":1'
        f',"recordId":"{record_id}"'
        f',"entityId":"{entity_id}"'
        f',"iv":"{b64(iv)}"'
        f',"ct":"{b64(ct)}"}}'
    )
    return {
        "name": name,
        "recordId": record_id,
        "entityId": entity_id,
        "accountId": account_id,
        "revision": revision,
        "schemaVersion": schema_version,
        "vaultKey": b64(vault_key),
        "iv": b64(iv),
        "plaintextUtf8": plaintext,
        "ct": b64(ct),
        "payloadJson": payload,
        "payloadSha256": hashlib.sha256(payload.encode("utf-8")).hexdigest(),
    }


def build_schema_vector(name, record_id, input_description, canonical_body):
    return {
        "name": name,
        "recordId": record_id,
        "inputDescription": input_description,
        "canonicalBodyJson": canonical_body,
        "canonicalBodySha256": hashlib.sha256(canonical_body.encode("utf-8")).hexdigest(),
    }


def main():
    hosts = json.dumps(
        {
            "hosts": [
                {
                    "id": "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31",
                    "label": "hetzner",
                    "hostname": "hetzner.example.com",
                    "port": 22,
                    "username": "valentin",
                    "keySecretId": "key-3f7c1b2e",
                    "passphraseSecretId": None,
                    "hostKeyType": "ed25519",
                    "hostKeyFingerprint": "SHA256:Zk5tVQ8m5r4Ykqz9Xw2C1bN7vJ3hG8sD0aF6uT4iQ2o",
                }
            ]
        },
        separators=(",", ":"),
        sort_keys=False,
    )
    workspaces = json.dumps(
        {
            "workspaces": [
                {"id": "ws-1", "name": "multiprompt", "hostId": "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31", "remotePath": "~/projects/multiprompt-android"}
            ]
        },
        separators=(",", ":"),
    )

    vectors = [
        build_vector(
            "single-record",
            "correct horse battery staple",
            {"hosts": hosts},
        ),
        build_vector(
            "multi-record-sorted",
            "multiprompt sync v1 passphrase",
            # Deliberately unsorted input: the envelope must sort record ids.
            {"workspaces": workspaces, "hosts": hosts},
        ),
        build_vector(
            "empty-record-body",
            "edge: empty record body",
            {"sessionState": ""},
        ),
    ]

    # Entity-record vectors seal directly with a vault key (no passphrase in the loop):
    # one D1 row per entity. Pinned keys keep the vectors deterministic.
    entity_vault_key = bytes(range(32))
    entity_vectors = [
        build_entity_vector(
            "entity-host-row",
            entity_vault_key,
            "hosts",
            "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31",
            hosts,
        ),
        build_entity_vector(
            "entity-session-state-encoded-key",
            entity_vault_key,
            "sessionState",
            "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31--my-session",
            json.dumps({"unread": True, "lastReadAt": 1757500000}, separators=(",", ":")),
        ),
    ]

    # Schema vectors: canonical plaintext record bodies mappers must emit — the two
    # languages must agree on field names/order/types before encryption. See the
    # "Schema vectors" section of docs/sync-protocol-v1.md.
    schema_vectors = [
        build_schema_vector(
            "schema-hosts-sorted-by-label",
            "hosts",
            "two hosts whose labels differ only by case; sorted case-insensitively by label, then id",
            json.dumps(
                {
                    "hosts": [
                        {
                            "id": "0aa1f4c2-5b8e-4d7f-9c3a-2e6b8d0f1a42",
                            "label": "alpha",
                            "hostname": "alpha.example.com",
                            "port": 22,
                            "username": "valentin",
                            "keySecretId": "key-alpha",
                            "passphraseSecretId": None,
                            "hostKeyType": "ed25519",
                            "hostKeyFingerprint": "SHA256:Aa1Bb2Cc3Dd4Ee5Ff6Gg7Hh8Ii9Jj0Kk1Ll2Mm3Nn4Oo",
                        },
                        {
                            "id": "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31",
                            "label": "Beta",
                            "hostname": "beta.example.com",
                            "port": 2222,
                            "username": "valentin",
                            "keySecretId": "key-beta",
                            "passphraseSecretId": None,
                            "hostKeyType": "ed25519",
                            "hostKeyFingerprint": "SHA256:Zk5tVQ8m5r4Ykqz9Xw2C1bN7vJ3hG8sD0aF6uT4iQ2o",
                        },
                    ]
                },
                separators=(",", ":"),
            ),
        ),
        build_schema_vector(
            "schema-workspaces-sorted-by-name",
            "workspaces",
            "two workspaces sorted case-insensitively by name",
            json.dumps(
                {
                    "workspaces": [
                        {"id": "ws-2", "name": "Archive stuff", "hostId": "0a4df4c2-8a4d-4d7f-9c3a-2e6b8d0f1a42", "remotePath": "~/archive"},
                        {"id": "ws-1", "name": "multiprompt", "hostId": "3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31", "remotePath": "~/projects/multiprompt-android"},
                    ]
                },
                separators=(",", ":"),
            ),
        ),
        build_schema_vector(
            "schema-session-state-archived-with-resume",
            "sessionState",
            "archived session with resumeAt and lastRead watermark; unread derived, never stored",
            '{"lastReadAt":1757500000,"archivedAt":1757410000,"resumeAt":1757600000}',
        ),
        build_schema_vector(
            "schema-session-state-active",
            "sessionState",
            "active session: archive keys null, optional fontScaleScope omitted",
            '{"lastReadAt":1757500000,"archivedAt":null,"resumeAt":null}',
        ),
    ]

    json.dump(
        {
            "protocol": PROTOCOL,
            "spec": "docs/sync-protocol-v1.md",
            "generatedBy": "scripts/gen-sync-fixtures.py",
            "note": "Stable cross-language vectors; Kotlin and zigshell must reproduce every envelope byte-for-byte.",
            "vectors": vectors,
            "entityVectors": entity_vectors,
            "schemaVectors": schema_vectors,
        },
        sys.stdout,
        indent=2,
        ensure_ascii=False,
    )
    sys.stdout.write("\n")


if __name__ == "__main__":
    assert os.isatty(sys.stdout.fileno()) is False or True  # allow piping or redirect
    main()
