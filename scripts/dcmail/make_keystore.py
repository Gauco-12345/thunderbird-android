#!/usr/bin/env python3
"""Build the DC Mail signing keystore from a secret passphrase.

The RSA key and the self-signed certificate are derived deterministically from
the passphrase, so every CI run produces the same signing identity and new
APKs install as updates over older ones. Nothing secret is stored in the
repository: only the passphrase (a GitHub Actions secret) is needed.

Usage: make_keystore.py <output.p12> <store-password>
The passphrase is read from the DCMAIL_SIGNING_PASSPHRASE environment variable.
"""

import datetime
import hashlib
import hmac
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

KEY_BITS = 2048
PUBLIC_EXPONENT = 65537
ALIAS = "dcmail"


class DeterministicRandom:
    """HMAC-SHA256 counter-mode byte stream seeded from the passphrase."""

    def __init__(self, seed: bytes):
        self._key = seed
        self._counter = 0

    def bytes(self, n: int) -> bytes:
        out = b""
        while len(out) < n:
            self._counter += 1
            out += hmac.new(self._key, self._counter.to_bytes(8, "big"), hashlib.sha256).digest()
        return out[:n]

    def int_bits(self, bits: int) -> int:
        return int.from_bytes(self.bytes((bits + 7) // 8), "big") >> ((8 - bits % 8) % 8)


def is_probable_prime(n: int, rnd: DeterministicRandom, rounds: int = 64) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for _ in range(rounds):
        a = 2 + rnd.int_bits(n.bit_length()) % (n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def gen_prime(bits: int, rnd: DeterministicRandom) -> int:
    while True:
        candidate = rnd.int_bits(bits) | (0b11 << (bits - 2)) | 1
        if (candidate - 1) % PUBLIC_EXPONENT != 0 and is_probable_prime(candidate, rnd):
            return candidate


def derive_key(passphrase: str) -> rsa.RSAPrivateKey:
    seed = hashlib.scrypt(passphrase.encode("utf-8"), salt=b"dcmail-signing-v1", n=2**15, r=8, p=1,
                          maxmem=2**26, dklen=32)
    rnd = DeterministicRandom(seed)
    while True:
        p = gen_prime(KEY_BITS // 2, rnd)
        q = gen_prime(KEY_BITS // 2, rnd)
        if p != q and (p * q).bit_length() == KEY_BITS:
            break
    if p < q:
        p, q = q, p
    n = p * q
    phi = (p - 1) * (q - 1)
    d = pow(PUBLIC_EXPONENT, -1, phi)
    numbers = rsa.RSAPrivateNumbers(
        p=p, q=q, d=d,
        dmp1=d % (p - 1), dmq1=d % (q - 1), iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(PUBLIC_EXPONENT, n),
    )
    return numbers.private_key()


def build_certificate(key: rsa.RSAPrivateKey) -> x509.Certificate:
    name = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "DC Mail"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Datacreate"),
        x509.NameAttribute(NameOID.COUNTRY_NAME, "DE"),
    ])
    # Fixed values keep the certificate byte-identical across runs.
    # RSA PKCS#1 v1.5 signatures are deterministic, unlike ECDSA.
    return (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(int.from_bytes(hashlib.sha256(b"dcmail-cert-v1").digest()[:16], "big") >> 1)
        .not_valid_before(datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc))
        .not_valid_after(datetime.datetime(2076, 1, 1, tzinfo=datetime.timezone.utc))
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    passphrase = os.environ.get("DCMAIL_SIGNING_PASSPHRASE", "")
    if len(passphrase) < 12:
        sys.exit("DCMAIL_SIGNING_PASSPHRASE is missing or shorter than 12 characters.")
    output, store_password = sys.argv[1], sys.argv[2]

    key = derive_key(passphrase)
    cert = build_certificate(key)
    data = pkcs12.serialize_key_and_certificates(
        name=ALIAS.encode(), key=key, cert=cert, cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(store_password.encode()),
    )
    with open(output, "wb") as f:
        f.write(data)
    print("Signing certificate SHA-256:", cert.fingerprint(hashes.SHA256()).hex(":").upper())


if __name__ == "__main__":
    main()
