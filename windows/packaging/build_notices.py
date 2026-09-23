"""Build a self-contained, offline license viewer from the shipped files.

No network access, model changes, executable changes or user data access.
Unknown JVM artifacts and missing notices fail the build for explicit review.
This inventory is not a certification of all transitive redistribution rights.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import zipfile
from pathlib import Path


PACKAGING = Path(__file__).resolve().parent
FAMILIES = (
    (r"kotlin-(stdlib|reflect)-", "Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    (r"kotlinx-coroutines-", "Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    (r"kotlinx-serialization-", "Kotlin Serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    (r"kotlinx-io-", "Kotlin IO", "Apache-2.0", "https://github.com/Kotlin/kotlinx-io"),
    (r"ktor-", "Ktor", "Apache-2.0", "https://github.com/ktorio/ktor"),
    (r"netty-", "Netty", "Apache-2.0 및 포함 제3자 고지", "https://github.com/netty/netty/tree/netty-4.2.16.Final"),
    (r"annotations-", "JetBrains Annotations", "Apache-2.0", "https://github.com/JetBrains/java-annotations"),
    (r"config-", "Typesafe Config", "Apache-2.0", "https://github.com/lightbend/config"),
    (r"alpn-api-", "Jetty ALPN API", "Apache-2.0 선택 적용 (EPL-1.0과 이중 허가)", "https://github.com/jetty-project/jetty-alpn"),
    (r"slf4j-api-", "SLF4J", "MIT", "https://github.com/qos-ch/slf4j"),
)
FIRST_PARTY = {"host.jar", "stream.jar", "translation.jar"}
NOTICE_NAME = re.compile(r"^(license|licence|notice|copying|copyright)([.\-_]|$)", re.I)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def classify_jar(name: str) -> tuple[str, str, str]:
    if name in FIRST_PARTY:
        return "MCastTalk", "Apache-2.0", "https://github.com/dashjeong/MCastTalk"
    for pattern, title, license_name, url in FAMILIES:
        if re.match(pattern, name):
            return title, license_name, url
    raise ValueError(f"Unreviewed JVM component: {name}")


def build(app: Path, repo: Path, packaging: Path = PACKAGING) -> dict:
    app, repo = app.resolve(), repo.resolve()
    output = app / "legal"
    if output.exists():
        raise ValueError("Refusing to replace an existing legal directory")
    documents: dict[str, dict] = {}
    components: list[dict] = []

    def document(data: bytes, label: str) -> str:
        text = data.decode("utf-8-sig")
        if not text.strip() or "\x00" in text:
            raise ValueError(f"Empty or binary notice: {label}")
        key = digest(data)
        record = documents.setdefault(key, {"sha256": key, "text": text, "sources": []})
        if label not in record["sources"]:
            record["sources"].append(label)
        return key

    def local(relative: str) -> str:
        target = (app / relative).resolve()
        if not target.is_relative_to(app):
            raise ValueError("Notice must be inside app image")
        return document(target.read_bytes(), relative)

    def component(name: str, license_name: str, url: str, keys: list[str], note: str = "") -> None:
        if not keys:
            raise ValueError(f"No local license for {name}")
        components.append(dict(name=name, license=license_name, source=url,
                               documents=list(dict.fromkeys(keys)), note=note))

    apache = document((repo / "LICENSE").read_bytes(), "MCastTalk / Apache-2.0 LICENSE")
    # Preserve the applicable first-party part, not Android-only model attributions.
    notice = (repo / "NOTICE").read_text(encoding="utf-8").split(
        "Third-Party Software and Model Attributions", 1)[0].rstrip("=\r\n ")
    first_notice = document((notice + "\n").encode(), "MCastTalk / first-party NOTICE")
    component("MCastTalk Windows 0.4.1", "Apache-2.0", "https://github.com/dashjeong/MCastTalk",
              [apache, first_notice], "자체 코드의 허가는 제3자 모델·라이브러리를 다시 허가하지 않습니다.")

    specs = [
        ("whisper.cpp CPU 실행기", "MIT", "https://github.com/ggml-org/whisper.cpp", "offline/assets/WHISPER-LICENSE.txt"),
        ("Qwen3-4B-Instruct-2507 / Q4_K_M 변환", "Apache-2.0", "https://huggingface.co/LMStudio-Community/Qwen3-4B-Instruct-2507-GGUF", "offline/assets/QWEN-LICENSE.txt"),
        ("llama.cpp b10964 CPU / Vulkan 실행기", "MIT", "https://github.com/ggml-org/llama.cpp", "offline/assets/LLAMA-LICENSE.txt"),
        ("Supertonic 3 음성합성 모델 / ONNX int8", "OpenRAIL-M — 용도 제한 있음", "https://huggingface.co/Supertone/supertonic-3", "offline/assets/SUPERTONIC-MODEL-LICENSE.txt"),
        ("Supertonic 변환 배포물의 예제 코드 고지", "MIT (모델 가중치의 허가가 아님)", "https://github.com/k2-fsa/sherpa-onnx", "offline/assets/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/LICENSE"),
        ("MeloTTS 중국어/영어 모델", "MIT", "https://github.com/myshell-ai/MeloTTS", "offline/assets/vits-melo-tts-zh_en/LICENSE"),
        ("CPython 3.12.14 및 포함 제3자 고지", "PSF 및 원문에 기재된 조건", "https://www.python.org/", "offline/python/LICENSE.txt"),
        ("NumPy 2.2.6 및 포함 네이티브 라이브러리", "BSD-3-Clause 및 포함 제3자 조건", "https://github.com/numpy/numpy", "offline/python/Lib/site-packages/numpy-2.2.6.dist-info/LICENSE.txt"),
        ("sherpa-onnx / sherpa-onnx-core 1.13.8", "Apache-2.0", "https://github.com/k2-fsa/sherpa-onnx", "offline/python/Lib/site-packages/sherpa_onnx-1.13.8.dist-info/licenses/LICENSE"),
    ]
    whisper_model = document((packaging / "licenses/WHISPER-MODEL-LICENSE.txt").read_bytes(),
                             "OpenAI Whisper / v20250625 LICENSE")
    component("OpenAI Whisper small / large-v3-turbo (양자화 모델)", "MIT", "https://github.com/openai/whisper",
              [whisper_model], "모델과 whisper.cpp 실행기의 저작권 고지는 별개입니다.")
    for name, license_name, url, relative in specs:
        note = ""
        if "OpenRAIL" in license_name:
            note = ("모델 이용 조건은 앱에서 별도로 확인합니다. AI 생성 표시 및 사용 제한이 적용됩니다. "
                    "의료 조언·의료 결과 해석, 사법·법집행·이민·망명 목적의 정보 생성·유포 등은 원문을 확인하세요. "
                    "아래 코드용 MIT 고지로 모델 조건을 대체할 수 없습니다.")
        component(name, license_name, url, [local(relative)], note)

    if (app / "offline/assets/native-runtime-evidence.json").is_file():
        component("정적 링크한 GCC 16.2 런타임 (libgcc/libstdc++/libgomp)",
                  "GPL-3.0 + GCC Runtime Library Exception 3.1",
                  "https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html",
                  [local("offline/assets/native-licenses/COPYING3.txt"),
                   local("offline/assets/native-licenses/COPYING.RUNTIME.txt")])
        component("MinGW-w64 정적 런타임", "포함 원문 참조",
                  "https://www.mingw-w64.org/",
                  [local("offline/assets/native-licenses/COPYING.MinGW-w64-runtime.txt")])
    else:
        # Historical shared-runtime images are still inspectable, but cannot
        # pass the static-runtime preflight used for newly staged installers.
        component("LLVM OpenMP (이전 llama.cpp 배포물)", "원문 참조",
                  "https://github.com/llvm/llvm-project",
                  [local("offline/assets/llama-cpu/LICENSE-LLVM-OpenMP")])

    netty = [document(p.read_bytes(), "Netty upstream / " + p.name)
             for p in sorted((packaging / "licenses").glob("NETTY-*.txt"))]
    if not all((packaging / "licenses" / name).is_file() for name in ("NETTY-LICENSE.txt", "NETTY-NOTICE.txt")):
        raise ValueError("Netty upstream LICENSE and NOTICE are required")
    netty_notice = (packaging / "licenses/NETTY-NOTICE.txt").read_text(encoding="utf-8")
    for name in set(re.findall(r"license/(LICENSE\.[A-Za-z0-9_.-]+\.txt)", netty_notice)):
        if not (packaging / "licenses" / ("NETTY-" + name)).is_file():
            raise ValueError("Missing Netty referenced notice: " + name)
    jars = sorted((app / "app").glob("*.jar"))
    if not jars:
        raise ValueError("No JVM artifacts found")
    for jar in jars:
        title, license_name, url = classify_jar(jar.name)
        keys = [] if title == "SLF4J" else ([*netty] if title == "Netty" else [apache])
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                if not entry.is_dir() and NOTICE_NAME.match(Path(entry.filename).name):
                    if entry.file_size > 5_000_000:
                        raise ValueError("Oversized embedded notice")
                    keys.append(document(archive.read(entry), f"app/{jar.name}!/{entry.filename}"))
        component(jar.name, license_name, url, keys, title)

    java_notices = [local(p.relative_to(app).as_posix())
                    for p in sorted((app / "runtime/legal").rglob("*")) if p.is_file()]
    component("Eclipse Temurin / OpenJDK 17.0.20.1+1", "GPL-2.0 + Classpath Exception 및 포함 제3자 조건",
              "https://adoptium.net/temurin/releases/", java_notices)
    extra = []
    for p in sorted((app / "offline").rglob("*")):
        if p.is_file() and (NOTICE_NAME.match(p.name) or "native-licenses" in p.parts):
            extra.append(local(p.relative_to(app).as_posix()))
    component("배포물에 포함된 추가 원문·저작권 고지", "각 원문 참조", "", extra)

    esc = html.escape
    rows = []
    for item in components:
        links = " · ".join(f'<a href="#doc-{key}">원문 {i + 1}</a>' for i, key in enumerate(item["documents"]))
        source = (f'<a href="{esc(item["source"], quote=True)}" target="_blank" rel="noopener noreferrer">공식 출처 ↗</a>'
                  if item["source"] else "배포물 내부 파일")
        rows.append(f'<tr><th scope="row">{esc(item["name"])}<small>{esc(item["note"])}</small></th>'
                    f'<td>{esc(item["license"])}</td><td>{source}<br>{links}</td></tr>')
    originals = []
    for key, value in documents.items():
        originals.append(f'<section id="doc-{key}"><h3>{esc(value["sources"][0])}</h3>'
                         f'<details><summary>포함 위치 · SHA-256</summary><pre>{esc(chr(10).join(value["sources"]))}\n{key}</pre></details>'
                         f'<pre>{esc(value["text"])}</pre><a href="#components">목록으로</a></section>')
    page = '''<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>MCastTalk 구성요소·라이선스 정보</title>
<style>body{font:16px/1.65 system-ui,sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;color:#182230;background:#fff}
a{color:#0754a3}table{border-collapse:collapse;width:100%}th,td{border:1px solid #cbd5e1;padding:12px;text-align:left;vertical-align:top}
small{display:block;font-weight:normal}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.55 monospace}
section{margin-top:48px;border-top:2px solid #94a3b8;padding-top:16px}h1{font-size:26px}.note{background:#eef5fc;padding:16px}
</style></head><body><h1>MCastTalk 구성요소·라이선스 정보</h1>
<p>이 문서는 설치 동의서가 아니라 포함된 코드·모델·실행 환경의 출처 및 이용 조건 안내입니다.</p>
<p class="note">모든 ‘원문’은 이 문서에 포함되어 인터넷 없이 열립니다. ‘공식 출처 ↗’만 외부 사이트로 이동하며 인터넷 연결이 필요합니다.
설치나 프로그램 실행에 공식 사이트 접속은 필요하지 않습니다. GitHub는 배포 플랫폼이며 자체가 라이선스는 아닙니다.</p>
<p>MCastTalk 자체 코드는 Apache-2.0으로 제공됩니다. 제3자 구성요소에는 각 원문이 적용됩니다.
Supertonic 모델의 별도 사용 조건 확인은 앱에서 유지되며, 프로그램 설치 확인이 이를 대신하지 않습니다.
고지 목록은 모든 전이 의존성의 배포 적합성에 대한 법률 인증을 의미하지 않습니다.</p>
<h2 id="components">구성요소 및 라이선스</h2><table><thead><tr><th>구성요소·버전</th><th>라이선스</th><th>출처·오프라인 원문</th></tr></thead><tbody>
'''+"\n".join(rows)+"</tbody></table><h2>라이선스 원문</h2>"+"\n".join(originals)+"</body></html>\n"
    # Write only after all required inputs validate; a failed inventory leaves no partial output.
    output.mkdir()
    encoded = page.encode("utf-8")
    (output / "THIRD_PARTY_LICENSES.html").write_bytes(encoded)
    (output / "INSTALLATION_OVERVIEW.txt").write_bytes((packaging / "INSTALLATION_OVERVIEW.txt").read_bytes())
    evidence = {"components": components, "documentCount": len(documents), "viewerSha256": digest(encoded),
                "documents": [{"sha256": key, "sources": value["sources"]} for key, value in documents.items()],
                "networkRequiredForViewer": False, "licenseConsentLocation": "application voice-model terms (unchanged)"}
    (output / "manifest.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return evidence


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=PACKAGING.parents[1])
    args = parser.parse_args()
    result = build(args.app, args.repo)
    print(json.dumps({"components": len(result["components"]), "documents": result["documentCount"],
                      "viewerSha256": result["viewerSha256"]}))
