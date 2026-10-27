"""
用轮换后的签名密钥给 release APK 签名并校验（APK Signature Scheme v3 密钥轮换）。

  python tools/sign_release.py app/build/outputs/apk/release/violet-mcp-<版本>-release.apk

读取 signing/rotation.properties（不进仓库、不进任何包，模板见 config-templates/rotation.properties.example）：
  lineage / oldStoreFile / oldKeyAlias / oldStorePassword / newStoreFile / newKeyAlias / newStorePassword / rotationMinSdk

签名后自动校验（任何一项不符都以非零退出，并删除签名结果）：
  1. apksigner verify 通过（minSdk 26 起）
  2. Android 9+（API 28+）看到的签名者是新证书；Android 8.x 看到的是旧证书（v2）
  3. APK 内嵌的 lineage 依次为 旧证书 → 新证书
口令通过环境变量传给 apksigner，不出现在命令行或日志中。
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = Path(os.environ.get("ANDROID_BUILD_TOOLS", "D:/android-sdk-public-build/build-tools/35.0.0"))
EXE = ".bat" if os.name == "nt" else ""


def load_props(p: Path) -> dict[str, str]:
    out = {}
    for line in p.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, _, v = line.partition("=")
            out[k.strip()] = v.strip()
    return out


def cert_sha256(ks: Path, alias: str, pw_env: str, env: dict) -> str:
    out = subprocess.run(["keytool", "-exportcert", "-keystore", str(ks), "-alias", alias, "-storepass:env", pw_env],
                         env=env, capture_output=True, check=True).stdout
    return hashlib.sha256(out).hexdigest()


def apksigner(args: list[str], env: dict) -> str:
    r = subprocess.run([str(BUILD_TOOLS / f"apksigner{EXE}")] + args, env=env, capture_output=True,
                       text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        raise RuntimeError(f"apksigner {args[0]} 失败：{r.stderr.strip()[:500]}")
    return r.stdout


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    apk = Path(sys.argv[1]).resolve()
    props_file = ROOT / "signing/rotation.properties"
    if not props_file.is_file():
        print(f"找不到 {props_file}")
        return 2
    p = load_props(props_file)
    env = dict(os.environ, VIOLET_OLD_PW=p["oldStorePassword"], VIOLET_NEW_PW=p["newStorePassword"])
    if "JAVA_HOME" in os.environ:
        env["PATH"] = str(Path(os.environ["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")

    zipalign = BUILD_TOOLS / ("zipalign.exe" if os.name == "nt" else "zipalign")
    if subprocess.run([str(zipalign), "-c", "4", str(apk)], capture_output=True).returncode != 0:
        print("APK 未对齐（应使用 Gradle 产出的 release 包）")
        return 1

    old_ks, new_ks = ROOT / p["oldStoreFile"], ROOT / p["newStoreFile"]
    old_sha = cert_sha256(old_ks, p["oldKeyAlias"], "VIOLET_OLD_PW", env)
    new_sha = cert_sha256(new_ks, p["newKeyAlias"], "VIOLET_NEW_PW", env)

    signed = apk.with_suffix(".signing.apk")
    shutil.copy2(apk, signed)
    try:
        apksigner(["sign",
                   "--ks", str(old_ks), "--ks-key-alias", p["oldKeyAlias"], "--ks-pass", "env:VIOLET_OLD_PW",
                   "--next-signer",
                   "--ks", str(new_ks), "--ks-key-alias", p["newKeyAlias"], "--ks-pass", "env:VIOLET_NEW_PW",
                   "--lineage", str(ROOT / p["lineage"]),
                   "--rotation-min-sdk-version", p.get("rotationMinSdk", "28"),
                   str(signed)], env)

        def signer_sha(extra: list[str]) -> set[str]:
            out = apksigner(["verify", "--print-certs"] + extra + [str(signed)], env)
            return set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out))

        all_ok = apksigner(["verify", "-v", "--min-sdk-version", "26", str(signed)], env)
        assert "Verifies" in all_ok, "apksigner verify 未通过"
        modern = signer_sha(["--min-sdk-version", "28"])
        legacy = signer_sha(["--min-sdk-version", "26", "--max-sdk-version", "27"])
        assert modern == {new_sha}, f"Android 9+ 的签名者应为新证书，实际 {modern}"
        assert legacy == {old_sha}, f"Android 8.x 的签名者应为旧证书，实际 {legacy}"
        lineage = re.findall(r"Signer #\d+ in lineage certificate SHA-256 digest: ([0-9a-f]{64})",
                             apksigner(["lineage", "--in", str(signed), "--print-certs"], env))
        assert lineage == [old_sha, new_sha], f"lineage 应为 旧→新，实际 {lineage}"
    except Exception as e:  # noqa: BLE001
        signed.unlink(missing_ok=True)
        print(f"签名失败：{e}")
        return 1
    signed.replace(apk)
    print(f"已签名：{apk.name}")
    print(f"  Android 9+（v3）签名证书 SHA-256：{new_sha}")
    print(f"  Android 8.x（v2）签名证书 SHA-256：{old_sha}")
    print(f"  lineage：{old_sha[:12]}… → {new_sha[:12]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
