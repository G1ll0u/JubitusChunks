# Jubitus Chunks

1.12.2 chunk pregenerator aiming to be compatible with everything.

## What this mod does

This mod **pregenerates chunks** for you, so the game generates terrain **ahead of time** instead of doing it while players explore.
It also pregenerates Millénaire villages correctly.

This generator is probably slower than our good old chunk-pregenerator but aims to be more compatible with terraingen mods.
The goal is to generate chunks like a player would do.

Pregenerate your world means:

* less lag when people travel,
  * No more Millénaire village generation lags
* fewer “freeze spikes” from new chunk generation,
* and eventually fewer worldgen crashes during normal play (because generation already happened).
* Vintage horizons <3

Video :
https://youtu.be/R2M3Zwqo27M

## Before you start: important things to know

### 1) Give the server or game a lot of RAM while pregenerating

Pregeneration loads and populates tons of chunks quickly, and many mods use extra memory during worldgen.

**Do this:**

* stop the server/game
* increase your `-Xmx` (max heap) as high as your machine safely allows

For a 16G machine : 12GB, `-Xms12G -Xmx12G`
32GB machine: 24GB, `-Xms24G -Xmx24G`
etc.

Note that more than 24GB is not good according to sources I don't remember.


### 2) This is heavy on CPU + disk

Pregeneration isn’t just RAM:

* CPU: terrain + structures + mod worldgen
* Disk: saving chunks fast

If your disk is slow, memory climbs faster because chunks can’t unload/snapshot fast enough.

### 3) The mod can recover after crashes / shutdowns

A really useful feature : **it can resume.**

Even if:

* the server/game crashes,
* you stop it,
* or the mod auto-stops to prevent OOM,

you can continue with:

```
/jubituschunks resume
```

---

## Commands

### Start pregeneration

```
/jubituschunks <radiusBlocks> [x] [z] [skipExisting|force]
```

**Defaults:**

* Center = **your current position** (if a player runs it)
* If console runs it: center = **world spawn**
* Mode default comes from config: `skipAlreadyGeneratedChunks` (usually **true**)

**Examples:**

* Generate 8000 block radius around you (or at spawn point if run from server) :

  ```
  /jubituschunks 8000
  ```

* Generate 8000 block radius around coordinates 0 0:

  ```
  /jubituschunks 8000 0 0
  ```

* Force generation even if chunks already exist (but allows to generate Millénaire villages on already generated terrain):

  ```
  /jubituschunks 8000 force
  ```

* Explicitly skip chunks that already exist (faster):

  ```
  /jubituschunks 8000 skipExisting
  ```

### Pause pregeneration

```
/jubituschunks pause
```

Stops the running pregen in your current dimension.

### Resume pregeneration

```
/jubituschunks resume [stepIndex] [x] [z]
```

### Cancel pregeneration
```
/jubituschunks cancel
```

**Defaults when you type only `/jubituschunks resume`:**

* It resumes from the **last saved progress step** in that dimension.
* It resumes using the same center and radius from the saved file.

**Examples:**

* Resume from the saved step automatically:

  ```
  /jubituschunks resume
  ```

* Resume from a specific step number:

  ```
  /jubituschunks resume 42000
  ```

* Resume from a specific step number, but change the spiral center (completely stupid):

  ```
  /jubituschunks resume 42000 0 0
  ```

---


## How progress works (steps vs blocks)

The mod prints progress like:

* `step=12345/125000`
* ETA (very imprecise but gives an idea)

A **step** is one “spiral position” the generator processes.

So when you do:

```
/jubituschunks 8000
```

You might see something like:

* **Steps: ~125000** (example number)
* That’s just the count of spiral positions to cover your area.

---

## Chunk Viewer (Real-time chunks map)

### Open the viewer

```
/jubituschunks view <radiusBlocks>
```

Opens a real-time chunk viewer GUI for the current dimension.

Legend may be hidden if GUI scale is too big

**Example:**

```
/jubituschunks view 8000
```

Opens the viewer for an 8000 block radius around the current pregen center (if running) or around you (if not running).

---

https://github.com/user-attachments/assets/6221de56-73c5-449a-a93b-64b71b9102bf


### Don’t run 10 other heavy things at the same time

Keep the server/game “quiet” while pregenerating:

* set peaceful mode (so there will be less entities and a bit better performance and may avoid eventual crashes related to entities)
* no player in server, if running from single player set view distance to minimum for maximum efficiency
* no big automation systems running (turn off your shit and disable other chunk-loaders)
* no chunk loaders (if possible)

### Always keep backups
---

