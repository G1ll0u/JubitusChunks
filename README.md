# Jubitus Chunks

1.12.2 chunk pregenerator aiming to be compatible with everything.

## What this mod does

This mod **pregenerates chunks** for you, so the game generates terrain **ahead of time** instead of doing it while players explore.
It also pregenerates Millénaire villages correctly.

This generator is probably slower than our old closed-source chunk-pregenerator but aims to be more compatible with terraingen mods, especially Millénaire.
The goal is to generate chunks like a player would do.

Pregenerate your world means:

* less lag when people travel,
  * No more Millénaire village generation lags
* fewer “freeze spikes” from new chunk generation,
* and eventually fewer worldgen crashes during normal play (because generation already happened).
* distant horizon <3


It generates chunks in a **square spiral** starting from a center point:

* it starts in the middle (your position by default),
* then goes out in a spiral like a growing square,
* until it reaches the requested radius.

Video :
https://youtu.be/R2M3Zwqo27M

https://github.com/user-attachments/assets/6221de56-73c5-449a-a93b-64b71b9102bf




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

Revert it after pregeneration done.

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

### Stop pregeneration

```
/jubituschunks stop
```

Stops the running pregen in your current dimension.

### Resume pregeneration

```
/jubituschunks resume [stepIndex] [x] [z]
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

* Resume from a specific step number, but change the spiral center (block coords):

  ```
  /jubituschunks resume 42000 0 0
  ```

---

## Chunk Viewer (Real-time chunks map)

### Open the viewer

```
/jubituschunks view <radiusBlocks>
```

Opens a **real-time chunk viewer GUI** for the current dimension.

This is meant for debugging pregeneration: it shows not only “progress”, but also what the server **actually has loaded** right now.

**Works on dedicated servers**: the command is executed server-side, and the GUI opens on the player client.

**Rules / behavior:**

* If a pregen task is running in this dimension:

  * Viewer centers on the **pregen center**
  * Viewer highlights the **spiral “head” working area** in red (the moving square of chunks your task is currently processing)
* If no pregen task is running:

  * Viewer centers on **your current position**
* Supports very large radii up to 12000+

### Colors / legend

* **Dark grey**: chunk area **not generated**
* **Grey**: chunk area **generated**
* **White**: chunk area **currently loaded**
* **Red overlay**: pregenerator **head / working square**
* **Green dot**: viewer **center**

### Notes

* The viewer is **live** and updates several times per second.
* For huge radii, the viewer uses a **scaled grid** (each pixel/cell may represent multiple chunks) so it stays fast and doesn’t spam packets.

**Example:**

```
/jubituschunks view 8000
```

Opens the viewer for an 8000 block radius around the current pregen center (if running) or around you (if not running).

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

### Important: steps are not blocks

Steps are the mod’s internal “spiral index”. They’re only used for resume / recovery.

---

## How to select part of the spiral (resume at any point)

If your run dies halfway through, you can restart from any step:

Example:

* You ran:

  ```
  /jubituschunks 8000
  ```
* It got to step 42000 and died.

You can recover with:

```
/jubituschunks resume 42000
```

That continues from “step 42000 in the spiral” and keeps going outward.

---

## Expanding later: go bigger without wasting time

A common workflow is:

### Phase 1: generate a smaller safe radius

```
/jubituschunks 8000
```

Let it finish.

### Phase 2: later expand the world further

```
/jubituschunks 12000
```

If `skipExisting` is enabled (default in most setups), the mod will:

* **skip chunks already generated** inside 8000
* and mostly work on the “new ring” between 8000 and 12000

### Phase 3: even bigger again

Same idea, repeat:

```
/jubituschunks 20000
```

---

## What you must be attentive of

### Watch memory usage

If heap memory climbs too high, my mod will stop your world and you will have to *restart* the game and resume the pregeneration.

Best practice:

* do pregen when nobody is playing
* give it lots of RAM
* don’t try insane radiuses in one go unless you know your modpack is stable

### Don’t run 10 other heavy things at the same time

Keep the server/game “quiet” while pregenerating:

* set peaceful mode (so there will be less entities and a bit better performance and may avoid crashes related to entities)
* no player in server, if running from single player set view distance to minimum for maximum efficiency
* no big automation systems running (turn off your shit and disable other chunk-loaders)
* no chunk loaders (if possible)

### If you changed worldgen mods/settings: skipping may keep old terrain

If you generate with one set of worldgen mods, then change mods later:

* skipping old chunks means they won’t update
* you’ll get borders / mismatches

In that case you might choose `force`, but that’s slow and can be messy.

### Always keep backups

Especially before huge runs.

---

## Quick cheat sheet

**Start around you:**

```
/jubituschunks 8000
```

**Stop:**

```
/jubituschunks stop
```

**Resume from saved progress:**

```
/jubituschunks resume
```

**Resume from step 42000:**

```
/jubituschunks resume 42000
```

**Expand later to 12000:**

```
/jubituschunks 12000
```

---
## Chunk Viewer (Real-time debug map)

### Open the viewer

```
/jubituschunks view <radiusBlocks>
```

Opens a **real-time chunk viewer GUI** for the current dimension.

This is meant for debugging pregeneration: it shows not only “progress”, but also what the server **actually has loaded** right now.

**Works on dedicated servers**: the command is executed server-side, and the GUI opens on the player client.

**Rules / behavior:**

* If a pregen task is running in this dimension:

  * Viewer centers on the **pregen center**
  * Viewer highlights the **spiral “head” working area** in red (the moving square of chunks your task is currently processing)
* If no pregen task is running:

  * Viewer centers on **your current position**
* Supports very large radii (same limits as pregeneration; not limited to 12k)

### Colors / legend

* **Dark grey**: chunk area **not generated**
* **Grey**: chunk area **generated**
* **White**: chunk area **currently loaded**
* **Red overlay**: pregenerator **head / working square**
* **Green dot**: viewer **center**

### Notes

* The viewer is **live** and updates several times per second.
* For huge radii, the viewer uses a **scaled grid** (each pixel/cell may represent multiple chunks) so it stays fast and doesn’t spam packets.

**Example:**
https://github.com/user-attachments/assets/6221de56-73c5-449a-a93b-64b71b9102bf


```
/jubituschunks view 8000
```

Opens the viewer for an 8000 block radius around the current pregen center (if running) or around you (if not running).

---
