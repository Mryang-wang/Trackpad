#!/bin/bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")" && pwd)"
identity_file="$source_root/macos/SigningIdentity.sha1"
certificate_name='Trackpad Console Local Signing'
keychain="$HOME/Library/Keychains/login.keychain-db"

if [[ -f "$identity_file" ]]; then
    identity="$(cat "$identity_file")"
    if security find-identity -p codesigning "$keychain" | /usr/bin/grep -Fq "$identity"; then
        printf '%s\n' 'Reusing the Trackpad signing identity in the login keychain.'
        exit 0
    fi
    printf '%s\n' 'The saved signing identity is missing from the login keychain. Restore it before building.' >&2
    exit 1
fi

if security find-certificate -c "$certificate_name" "$keychain" >/dev/null 2>&1; then
    security find-certificate -c "$certificate_name" -Z "$keychain" | awk '/SHA-1 hash:/ { print $3; exit }' > "$identity_file"
    printf '%s\n' 'Recovered the existing Trackpad signing identity.'
    exit 0
fi

umask 077
signing_work="$(mktemp -d /tmp/trackpad-signing.XXXXXX)"
trap 'rm -rf "$signing_work"' EXIT
cat > "$signing_work/certificate.conf" <<'CONFIG'
[req]
distinguished_name = name
x509_extensions = usage
prompt = no
[name]
CN = Trackpad Console Local Signing
[usage]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, digitalSignature, keyCertSign
extendedKeyUsage = critical, codeSigning
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
CONFIG
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -config "$signing_work/certificate.conf" \
    -keyout "$signing_work/private-key.pem" -out "$signing_work/certificate.pem" 2>/dev/null
openssl rand -hex 24 > "$signing_work/password"
openssl pkcs12 -export -name "$certificate_name" \
    -inkey "$signing_work/private-key.pem" -in "$signing_work/certificate.pem" \
    -macalg sha1 -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES \
    -passout "file:$signing_work/password" -out "$signing_work/identity.p12"
python3 - "$signing_work" "$keychain" <<'PYIMPORT'
import subprocess
import sys
from pathlib import Path
work = Path(sys.argv[1])
password = (work / "password").read_text().strip()
result = subprocess.run(["security", "import", str(work / "identity.p12"), "-k", sys.argv[2],
                         "-P", password, "-T", "/usr/bin/codesign"])
raise SystemExit(result.returncode)
PYIMPORT
openssl x509 -in "$signing_work/certificate.pem" -outform DER -out "$signing_work/certificate.cer"
security add-trusted-cert -r trustRoot -p codeSign -k "$keychain" "$signing_work/certificate.cer"
openssl x509 -in "$signing_work/certificate.pem" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d ':' > "$identity_file"
printf '%s\n' 'Created the fixed Trackpad signing identity. Its private key is stored in the login keychain.'
