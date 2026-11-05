# Advance Lighting (Mindustry v8 Fork)

> [!WARNING]
> **Unofficial Fork:** This is an unofficial fork of the **Advance Lighting** mod for Mindustry, updated and maintained for **Mindustry v8**.

---

## About the Fork

* **Compatibility:** Ported specifically for **Mindustry v8**.
* **Development:** Maintained independently by the fork author without involvement from the original creator.
* **Issues:** Report bugs and suggestions related to this v8 version in this repository's Issues section.

---

## Original Project & License

* **Original Mod:** [Advance-Lighting by EyeOfDarkness](https://github.com/EyeOfDarkness/Advance-Lighting)
* **Original Author:** `EyeOfDarkness`
* **License:** [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0.html) — Open-source rules apply.

---

## Intermod Support (Modding Guide)

This mod allows external mods to include glowing textures without direct dependencies:

> [!NOTE]
> **Internal Support (Inside your mod):**
> * Suffix `-advance-light` for partial glow.
> * Suffix `-advance-light-replace` to replace block main textures (glass/gloss).
> * Heat parts: `-advance-light-heat` (or `-advance-light-heat-l` / `-r` for shaded turrets).

> [!TIP]
> **External Support (Separate addon mod named `advance-lighting-<modname>`):**
> * Same base sprite name for partial glow.
> * Suffix `-replace` for sprite replacement.
> * Heat parts: `-heat`, `-heat-l`, `-heat-r`.

* **Limitations:** Texture replacement applies primarily to blocks (main region, conduits/ducts) and units. Custom classes storing textures differently may not be supported.

---

## Building from Source

### Prerequisites

* **JDK 17+**

### Quick Desktop Build

```bash
./gradlew jar
# Output jar located in build/libs (Desktop only)
```

### Full / Cross-Platform Build (Desktop + Android)

#### Option 1: GitHub Actions (Recommended)

Push your code to GitHub. Actions automatically builds multiplatform `.jar` artifacts under the **Actions** tab of your repository.

#### Option 2: Local Build

1. Install **Android SDK** (API 30, build-tools 30.0.1+) and set `ANDROID_HOME`.
2. Add build tools to `PATH`.
3. Run:

   ```bash
   ./gradlew deploy
   ```

> [!IMPORTANT]
> **Gradle Dependencies Note:**
> All Mindustry/Arc dependencies in `build.gradle` must use `compileOnly`, never `implementation` (to prevent bundling game source into the mod jar).

---

## Installation

1. Download the compiled `.jar` from the [Releases](../../releases) section.
2. Place it into your Mindustry `mods` folder or import via the in-game Mods menu.
3. Restart Mindustry v8.