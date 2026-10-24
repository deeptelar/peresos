# Contributing to Peresós

Thank you for helping a sloth. This guide covers where to start, how to build and test, and the few
rules the app keeps.

## Where to start

- **Try it on your computer.** The issues labeled [testing](https://github.com/deeptelar/peresos/issues?q=is%3Aissue+is%3Aopen+label%3Atesting)
  each ask for one system: Windows, macOS, a Debian or Ubuntu, another Linux. You need no code and
  no GPU: the installer, the window and `peresos doctor` work without Ollama. Comment with what you
  saw, and the report of `peresos doctor --report`.
- **A first contribution:** the issues labeled [good first issue](https://github.com/deeptelar/peresos/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22),
  like a new item for its wardrobe, need neither Ollama nor a GPU.
- **Something larger:** the issues labeled [help wanted](https://github.com/deeptelar/peresos/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22).

Comment on an issue before you start, so that two people do not make the same thing.

## Found a bug

Run `peresos doctor --report`. It checks what Peresós needs, writes a file with the checks, your
system and the last failures of its log (`~/.peresos/logs/peresos.log`), and offers to open a new
issue with it filled in. The file has no word of your pile, your diary or what Peresós knows about
you, but read it before you send it: it has the paths of your computer. Then say what you did and
what you expected.

## Build and test

You need a JDK 17 or newer. Ollama and Gemma 4 (`ollama pull gemma4:12b`) are needed only to see it
eat for real: the tests use a scripted model.

```bash
git clone https://github.com/deeptelar/peresos.git
cd peresos
./gradlew test
```

| Command | What it does |
|---|---|
| `./gradlew test` | All the tests, without a model: the graph, the window, the wardrobe, the doctor |
| `./gradlew test --tests "*DoctorTest*"` | One test class |
| `./gradlew installDist` | Builds the app into `build/install/peresos`; run it with `build/install/peresos/bin/peresos` |
| `./gradlew run --args="window --season=winter --weather=snow"` | The window in another season or weather |
| `./gradlew soak -Psteps=5000` | Clicks through the window for a long time and checks every step; `-Pseed=7` repeats a walk, `-Pgemma` uses the real model |
| `./gradlew appImage` | Peresós with its own Java in `build/jpackage`, for the system you build on |
| `packaging/linux.sh` | After `appImage` on Linux: the `.deb` and the `.tar.gz` in `build/dist` |

`PERESOS_HOME=/tmp/sloth` gives it another home, so you can try things without touching your own
sloth, and `peresos --hungry` lets it eat more than once a day.

### Try an installer before a release

The installers take their files from another folder with `PERESOS_FROM`:

```bash
./gradlew appImage && packaging/linux.sh
PERESOS_LOCAL=1 PERESOS_FROM=$PWD/build/dist sh install.sh    # in your home, without sudo
```

On Windows, `$env:PERESOS_FROM = "$pwd\build\dist"; ./install.ps1` after zipping
`build/jpackage/peresos` into `build/dist/peresos-windows-x64.zip`. The [Package workflow](.github/workflows/package.yml)
does all of this on Linux, macOS and Windows for every pull request.

## Where things are

| File | What is in it |
|---|---|
| [`Sloth.kt`](src/main/kotlin/dev/deeptelar/peresos/Sloth.kt) | The state and the graph of Telar: wake, pick, chew, swallow, diary |
| [`Main.kt`](src/main/kotlin/dev/deeptelar/peresos/Main.kt) | The `peresos` command and its subcommands |
| [`Home.kt`](src/main/kotlin/dev/deeptelar/peresos/Home.kt), [`Pile.kt`](src/main/kotlin/dev/deeptelar/peresos/Pile.kt) | `~/.peresos`: the pile, the checkpoints, the facts, the locks; and the model in Ollama |
| [`Doctor.kt`](src/main/kotlin/dev/deeptelar/peresos/Doctor.kt), [`Log.kt`](src/main/kotlin/dev/deeptelar/peresos/Log.kt) | `peresos doctor` and its report, and the log of failures |
| [`Portrait.kt`](src/main/kotlin/dev/deeptelar/peresos/Portrait.kt), [`Wardrobe.kt`](src/main/kotlin/dev/deeptelar/peresos/Wardrobe.kt), [`Canvas.kt`](src/main/kotlin/dev/deeptelar/peresos/Canvas.kt) | The pixel art of the sloth, what it wears, and the canvas both are drawn on |
| [`Tray.kt`](src/main/kotlin/dev/deeptelar/peresos/Tray.kt), [`Window.kt`](src/main/kotlin/dev/deeptelar/peresos/Window.kt), [`SlothModel.kt`](src/main/kotlin/dev/deeptelar/peresos/SlothModel.kt) | The tray icon, the window and its pages, and what its buttons do |
| [`Habitat.kt`](src/main/kotlin/dev/deeptelar/peresos/Habitat.kt), [`Weather.kt`](src/main/kotlin/dev/deeptelar/peresos/Weather.kt), [`Poke.kt`](src/main/kotlin/dev/deeptelar/peresos/Poke.kt) | The scene around it, the season and the weather of Open-Meteo, and what it says when touched |
| [`PixelUi.kt`](src/main/kotlin/dev/deeptelar/peresos/PixelUi.kt) | The pixel-art buttons and fields of the window |
| `packaging/`, `install.sh`, `install.ps1` | The packages and their installers |

## What Peresós keeps

- **Nothing leaves your computer.** The pile, the diary and the facts stay in `~/.peresos`, and the
  model runs in the local Ollama. Anything that goes out must be chosen by the person, as the town of
  `peresos place` is, and a report never carries the pile, the diary or the facts.
- **It works without a model to test.** A change to the graph comes with a test in `SlothTest` that
  runs it with a `ScriptedChatModel`.
- **The terminal and the window are the same sloth.** What one can do, the other can, on the same
  home, and only one wakes it at a time.
- **It works without a tray, and without a desktop.** The window then has a frame; the terminal
  needs neither.
- **Pixel art is drawn relative to the sloth**, never at fixed pixels, so it fits every phase.
- **Its words are short and plain**, in the terminal, the window and the code: say what happens, not
  how clever it is. A comment says why, not what.

## Pull requests

- One change per pull request, with its tests. `./gradlew test` passes.
- A change you can see comes with a screenshot.
- Say which issue it closes.

Releases are made by the maintainers: the `version` in `build.gradle.kts`, then a tag like
`v1.0.1`, and the Package workflow publishes the packages that the installers download.
