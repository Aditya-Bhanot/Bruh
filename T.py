#!/usr/bin/env python3
"""
Attrium Zero-Source-Edit Automated Obfuscation & Release Workflow Installer (Fabric 1.21.11)
- Never modifies your Java source files in src/.
- Generates fresh randomized ProGuard identifier dictionaries on every build.
- Strips SourceFile, LineNumberTable, LocalVariableTable, and MethodParameters from the release JAR.
- Updates build.gradle `obfuscateJar` task and writes `.github/workflows/release.yml` so every push
  to `master` automatically builds and publishes ONLY the hardened `*-obf.jar`.
"""
import base64
import os
import re
import sys

PG_B64 = "IyBBdHRyaXVtIEF1dG9tYXRlZCBSZWxlYXNlIEhhcmRlbmluZyBSdWxlcyAoRmFicmljIDEuMjEuMTEgKyBKYXZhIDIxICsgRm9sZENyYWZ0TGF1bmNoZXIgU2FmZSkKIyBPcmlnaW5hbCBzb3VyY2UgZmlsZXMgaW4gc3JjLyBhcmUgTkVWRVIgbW9kaWZpZWQuCi1kb250c2hyaW5rCi1kb250b3B0aW1pemUKLWRvbnR3YXJuICoqCi1pZ25vcmV3YXJuaW5ncwotdXNldW5pcXVlY2xhc3NtZW1iZXJuYW1lcwotb3ZlcmxvYWRhZ2dyZXNzaXZlbHkKLWFsbG93YWNjZXNzbW9kaWZpY2F0aW9uCgojIFJhbmRvbWl6ZWQgcGVyLWJ1aWxkIGlkZW50aWZpZXIgZGljdGlvbmFyaWVzIChnZW5lcmF0ZWQgZnJlc2ggZXZlcnkgYnVpbGQgYnkgR3JhZGxlKQotb2JmdXNjYXRpb25kaWN0aW9uYXJ5IGJ1aWxkL3Byb2d1YXJkLWRpY3QudHh0Ci1jbGFzc29iZnVzY2F0aW9uZGljdGlvbmFyeSBidWlsZC9wcm9ndWFyZC1jbGFzcy1kaWN0LnR4dAotcGFja2FnZW9iZnVzY2F0aW9uZGljdGlvbmFyeSBidWlsZC9wcm9ndWFyZC1wa2ctZGljdC50eHQKCiMgU3RyaXAgU291cmNlRmlsZSwgTGluZU51bWJlclRhYmxlLCBMb2NhbFZhcmlhYmxlVGFibGUsIE1ldGhvZFBhcmFtZXRlcnMgc28gZGVjb21waWxlcnMKIyBsb3NlIGFsbCB2YXJpYWJsZSBuYW1lcywgZmlsZSBuYW1lcywgYW5kIGxpbmUgbWFwcGluZ3MuCi1rZWVwYXR0cmlidXRlcyAqQW5ub3RhdGlvbiosU2lnbmF0dXJlLElubmVyQ2xhc3NlcyxFbmNsb3NpbmdNZXRob2QKCiMgMS4gS2VlcCBGYWJyaWMgRW50cnlwb2ludHMgKHJlcXVpcmVkIGJ5IGZhYnJpYy5tb2QuanNvbikKLWtlZXAgY2xhc3MgKiBpbXBsZW1lbnRzIG5ldC5mYWJyaWNtYy5hcGkuTW9kSW5pdGlhbGl6ZXIgeyAqOyB9Ci1rZWVwIGNsYXNzICogaW1wbGVtZW50cyBuZXQuZmFicmljbWMuYXBpLkNsaWVudE1vZEluaXRpYWxpemVyIHsgKjsgfQota2VlcCBjbGFzcyAqIGltcGxlbWVudHMgbmV0LmZhYnJpY21jLmFwaS5EZWRpY2F0ZWRTZXJ2ZXJNb2RJbml0aWFsaXplciB7ICo7IH0KCiMgMi4gS2VlcCBNaXhpbiBjbGFzc2VzICYgaG9va3MgKHJlcXVpcmVkIGJ5IFNwb25nZVBvd2VyZWQgTWl4aW4gcnVudGltZSkKLWtlZXAgQG9yZy5zcG9uZ2Vwb3dlcmVkLmFzbS5taXhpbi5NaXhpbiBjbGFzcyAqIHsgKjsgfQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1Lm1peGluLioqIHsgKjsgfQoKIyAzLiBLZWVwIGV4YWN0IHJlZmxlY3Rpb24tZGlzY292ZXJlZCBuYW1lcyAoTW9kdWxlTWFuYWdlciwgQ2xpY2tHVUkgbW9kdWxlL3NldHRpbmcgZGlzY292ZXJ5KQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1Lk1haW4geyAqOyB9Ci1rZWVwIGNsYXNzIGF0dHJpdW0ucnUubWFuYWdlci5NYW5hZ2VyIHsgKjsgfQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1LmFwaS5tb2R1bGUuTW9kdWxlIHsgKjsgfQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1LmFwaS5tb2R1bGUuTW9kdWxlQ2F0ZWdvcnkgeyAqOyB9Ci1rZWVwIGNsYXNzIGF0dHJpdW0ucnUuYXBpLm1vZHVsZS5Nb2R1bGVNYW5hZ2VyIHsgKjsgfQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1Lm1hbmFnZXIuTW9kdWxlTWFuYWdlciB7ICo7IH0KLWtlZXAgY2xhc3MgYXR0cml1bS5ydS5hcGkuc2V0dGluZ3MuKiogeyAqOyB9CgojIEtlZXAgbW9kdWxlIGNsYXNzIG5hbWVzICYgU2V0dGluZyBmaWVsZHMgKHVzZWQgYnkgQ2xpY2tHVUkgcmVmbGVjdGlvbiksCiMgd2hpbGUgb2JmdXNjYXRpbmcgYWxsIHByaXZhdGUvaW50ZXJuYWwgbWV0aG9kcyAmIGZpZWxkcyBpbnNpZGUgZXZlcnkgbW9kdWxlIQota2VlcCBjbGFzcyAqIGV4dGVuZHMgYXR0cml1bS5ydS5hcGkubW9kdWxlLk1vZHVsZSB7CiAgICA8aW5pdD4oLi4uKTsKICAgIHB1YmxpYyAqOwogICAgYXR0cml1bS5ydS5hcGkuc2V0dGluZ3MuKiogKjsKfQoKIyBLZWVwIHB1YmxpYyBjcm9zcy1taXhpbiBob29rcyBpbiBtYW5hZ2VycyAmIENsaWNrR1VJIHdoaWxlIG9iZnVzY2F0aW5nIHByaXZhdGUvaW50ZXJuYWwgaW50ZXJuYWxzCi1rZWVwIGNsYXNzIGF0dHJpdW0ucnUuc2NyZWVucy5HbGFzc0NsaWNrR3VpU2NyZWVuIHsKICAgIHB1YmxpYyAqOwp9Ci1rZWVwIGNsYXNzIGF0dHJpdW0ucnUubWFuYWdlci4qKiB7CiAgICBwdWJsaWMgKjsKfQota2VlcCBjbGFzcyBhdHRyaXVtLnJ1LnV0aWxzLioqIHsKICAgIHB1YmxpYyAqOwp9CgojIDQuIEFnZ3Jlc3NpdmVseSByYW5kb21pemUgJiByZW5hbWUgYXR0cml1bS5ydS5hdXRoLioqIChjbGFzc2VzLCBtZXRob2RzLCBmaWVsZHMpCi1rZWVwY2xhc3NtZW1iZXJzIGNsYXNzICogZXh0ZW5kcyBuZXQubWluZWNyYWZ0LmNsaWVudC5ndWkuc2NyZWVucy5TY3JlZW4gewogICAgcHVibGljIHZvaWQgcmVuZGVyKC4uLik7CiAgICBwdWJsaWMgdm9pZCByZW5kZXJCYWNrZ3JvdW5kKC4uLik7CiAgICBwcm90ZWN0ZWQgdm9pZCBpbml0KCk7CiAgICBwdWJsaWMgdm9pZCB0aWNrKCk7CiAgICBwdWJsaWMgYm9vbGVhbiBzaG91bGRDbG9zZU9uRXNjKCk7CiAgICBwdWJsaWMgYm9vbGVhbiBpc1BhdXNlU2NyZWVuKCk7Cn0K"
YML_B64 = "bmFtZTogQnVpbGQgJiBQdWJsaXNoIEhhcmRlbmVkIFJlbGVhc2UKCm9uOgogIHB1c2g6CiAgICBicmFuY2hlczogWyAibWFzdGVyIiBdCiAgd29ya2Zsb3dfZGlzcGF0Y2g6CgpwZXJtaXNzaW9uczoKICBjb250ZW50czogd3JpdGUKCmpvYnM6CiAgYnVpbGQtYW5kLXJlbGVhc2U6CiAgICBydW5zLW9uOiB1YnVudHUtbGF0ZXN0CiAgICBzdGVwczoKICAgICAgLSBuYW1lOiBDaGVja291dCByZXBvc2l0b3J5CiAgICAgICAgdXNlczogYWN0aW9ucy9jaGVja291dEB2NAoKICAgICAgLSBuYW1lOiBTZXQgdXAgSkRLIDIxCiAgICAgICAgdXNlczogYWN0aW9ucy9zZXR1cC1qYXZhQHY0CiAgICAgICAgd2l0aDoKICAgICAgICAgIGphdmEtdmVyc2lvbjogJzIxJwogICAgICAgICAgZGlzdHJpYnV0aW9uOiAndGVtdXJpbicKCiAgICAgIC0gbmFtZTogU2V0dXAgR3JhZGxlCiAgICAgICAgdXNlczogZ3JhZGxlL2FjdGlvbnMvc2V0dXAtZ3JhZGxlQHY0CgogICAgICAtIG5hbWU6IEJ1aWxkICYgT2JmdXNjYXRlIFJlbGVhc2UgSkFSCiAgICAgICAgcnVuOiB8CiAgICAgICAgICBjaG1vZCAreCBncmFkbGV3CiAgICAgICAgICAuL2dyYWRsZXcgY2xlYW4gYnVpbGQgb2JmdXNjYXRlSmFyIC0tbm8tZGFlbW9uCgogICAgICAtIG5hbWU6IFZlcmlmeSBIYXJkZW5lZCBKQVIgRXhpc3RzCiAgICAgICAgcnVuOiB8CiAgICAgICAgICBscyAtbGggYnVpbGQvbGlicy8qLW9iZi5qYXIKCiAgICAgIC0gbmFtZTogUHVibGlzaCBSb2xsaW5nIExhdGVzdCBSZWxlYXNlIChIYXJkZW5lZCBKQVIgT25seSkKICAgICAgICB1c2VzOiBzb2Z0cHJvcHMvYWN0aW9uLWdoLXJlbGVhc2VAdjIKICAgICAgICB3aXRoOgogICAgICAgICAgdGFnX25hbWU6IGxhdGVzdAogICAgICAgICAgbmFtZTogQXR0cml1bSBMYXRlc3QgUmVsZWFzZQogICAgICAgICAgYm9keTogIkF1dG9tYXRlZCBoYXJkZW5lZCBidWlsZCBmcm9tIGNvbW1pdCBgJHt7IGdpdGh1Yi5zaGEgfX1gLiIKICAgICAgICAgIHByZXJlbGVhc2U6IGZhbHNlCiAgICAgICAgICBtYWtlX2xhdGVzdDogdHJ1ZQogICAgICAgICAgZmlsZXM6IGJ1aWxkL2xpYnMvKi1vYmYuamFyCg=="

GRADLE_BUILDSCRIPT = """buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath 'com.guardsquare:proguard-gradle:7.6.1'
    }
}
"""

GRADLE_OBF_BLOCK = """// --- Attrium Automated Randomized Release Obfuscation (Zero Source Modification) ---
tasks.register('generateObfDictionaries') {
    doLast {
        def rng = new java.security.SecureRandom()
        def alpha = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
        def writeDict = { File target, int count, int minLen, int maxLen ->
            target.parentFile.mkdirs()
            def seen = new LinkedHashSet<String>()
            while (seen.size() < count) {
                int len = minLen + rng.nextInt(maxLen - minLen + 1)
                def sb = new StringBuilder(len)
                for (int i = 0; i < len; i++) {
                    sb.append(alpha.charAt(rng.nextInt(alpha.length())))
                }
                seen.add(sb.toString())
            }
            target.text = seen.join('\\n') + '\\n'
        }
        writeDict(file('build/proguard-dict.txt'), 1200, 3, 7)
        writeDict(file('build/proguard-class-dict.txt'), 600, 4, 8)
        writeDict(file('build/proguard-pkg-dict.txt'), 120, 2, 5)
    }
}

tasks.register('obfuscateJar', proguard.gradle.ProGuardTask) {
    dependsOn tasks.named('remapJar'), tasks.named('generateObfDictionaries')
    configuration file('proguard-rules.pro')

    injars tasks.named('remapJar').flatMap { it.archiveFile }
    outjars layout.buildDirectory.file("libs/${project.archives_base_name}-${project.version}-obf.jar")

    doFirst {
        def javaHome = System.getProperty('java.home')
        def jmodsDir = file("${javaHome}/jmods")
        if (jmodsDir.exists()) {
            jmodsDir.listFiles().each { f ->
                if (f.name.endsWith('.jmod')) {
                    libraryjars(f, filter: '!**.jar;!module-info.class')
                }
            }
        }
        libraryjars configurations.compileClasspath
        libraryjars configurations.runtimeClasspath
    }
}
"""


def patch_build_gradle(cwd):
    bg_path = os.path.join(cwd, "build.gradle")
    if not os.path.isfile(bg_path):
        print("[WARN] build.gradle not found.")
        return
    with open(bg_path, "r", encoding="utf-8") as f:
        src = f.read()

    if "proguard-gradle" not in src:
        src = GRADLE_BUILDSCRIPT + "\n" + src

    # Replace previous Step 5 obfuscateJar block if present, otherwise append
    old_marker = "// --- Attrium Step 5:"
    new_marker = "// --- Attrium Automated Randomized Release Obfuscation"
    if new_marker in src:
        idx = src.index(new_marker)
        src = src[:idx].rstrip() + "\n\n" + GRADLE_OBF_BLOCK
    elif old_marker in src:
        idx = src.index(old_marker)
        src = src[:idx].rstrip() + "\n\n" + GRADLE_OBF_BLOCK
    else:
        src = src.rstrip() + "\n\n" + GRADLE_OBF_BLOCK

    with open(bg_path, "w", encoding="utf-8") as f:
        f.write(src)
    print("[PATCHED] build.gradle (added generateObfDictionaries + obfuscateJar)")


def main():
    cwd = os.getcwd()
    if not os.path.isdir(os.path.join(cwd, "src", "main")):
        print("[ERROR] Run this script from your project root (~/Meow/Bruh).")
        sys.exit(1)

    with open(os.path.join(cwd, "proguard-rules.pro"), "wb") as f:
        f.write(base64.b64decode(PG_B64))
    print("[OK] Wrote proguard-rules.pro")

    wf_dir = os.path.join(cwd, ".github", "workflows")
    os.makedirs(wf_dir, exist_ok=True)
    with open(os.path.join(wf_dir, "release.yml"), "wb") as f:
        f.write(base64.b64decode(YML_B64))
    print("[OK] Wrote .github/workflows/release.yml")

    patch_build_gradle(cwd)
    print("\nAutomated release obfuscation pipeline installed!")
    print("Local Termux test:  ./gradlew clean build obfuscateJar --no-daemon")
    print("GitHub auto-build:  git add proguard-rules.pro build.gradle .github/workflows/release.yml && git commit -m 'Auto obfuscate release' && git push")


if __name__ == "__main__":
    main()