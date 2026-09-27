#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Compiles production boundaries and exercises memory-only transports; no phone/network.
set -eu
cd "$(dirname "$0")/.."
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT HUP INT TERM
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 17 -Xlint:all -Werror -d "$output" \
  src/com/android/imsserviceentitlement/utils/HttpsUrl.java \
  src/com/android/imsserviceentitlement/utils/CarrierTransport.java \
  src/com/android/imsserviceentitlement/utils/CarrierXml.java \
  tests/host/com/android/imsserviceentitlement/utils/CarrierBoundaryTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$output" \
  com.android.imsserviceentitlement.utils.CarrierBoundaryTest
python3 - <<'PY'
from pathlib import Path
import re
import xml.etree.ElementTree as ET
bp = Path('Android.bp').read_text()
assert not re.search(r'"(?:firebase-|play-services-|transport-)', bp)
for source in Path('src').rglob('*.java'):
    assert not re.search(r'import com\.google\.(?:firebase|android\.gms)', source.read_text())
android = '{http://schemas.android.com/apk/res/android}'
root = ET.parse('AndroidManifest.xml').getroot()
for component in root.find('application'):
    if component.get(android + 'exported') == 'true':
        assert component.get(android + 'permission') in (
            'android.permission.MODIFY_PHONE_STATE', 'android.permission.BIND_JOB_SERVICE'), component.attrib
assert not any('.fcm.' in e.get(android + 'name', '') for e in root.iter())
print('Source dependency and exported-component checks: PASS')
PY
