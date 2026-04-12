# Nexori Public API Demo

This repository is the official example third-party mod used to validate and
demonstrate Nexori's public minigame API from a separate plugin.

It is not the main Nexori product.
It is a companion demo that shows how another mod can build custom gameplay on
top of Nexori's match launch and return flow.

## What This Demo Proves

The demo proves that a separate mod can:

- detect whether a player is inside an active Nexori match
- wait for Nexori's initial player placement to finish
- run custom game logic without owning Nexori internals
- resolve player outcomes manually through the public API
- let Nexori handle the return-to-lobby flow

## Current Example: Mid Capture

The demo currently implements a simple `Mid Capture` minigame:

- players launch into a Nexori match instance
- players fight for control of one center zone
- progress builds while one player holds the zone alone
- once a player reaches full control, the demo resolves the outcome
- Nexori performs the delayed return flow

The HUD in this repo is intentionally simple and focused on proving API usage,
not on replacing a full production UI pack.

## Dependency

This project depends on the main Nexori plugin:

- [nexori-plugin](https://github.com/hyjn-nexori/nexori-plugin)

The manifest dependency is:

```json
"Dependencies": {
  "Nexori:NexoriPlugin": "*"
}
```

## Public API Story

The design goal is intentionally narrow:

- Nexori launches the match
- your mod owns its gameplay rules
- your mod decides who won or lost
- your mod calls Nexori to resolve that outcome

This demo exists to show that story clearly.

## Important Interfaces

The public API surface lives in the main plugin:

- [NexoriMinigameApi.java](D:/JanielNunez/hyjn-nexori/nexori-plugin/src/main/java/io/github/hyjn/nexori/plugin/api/minigame/NexoriMinigameApi.java)

The most important calls for this demo are:

- `findActiveMatchId(...)`
- `findActivePlayerUuid(...)`
- `findMatchPlacementState(...)`
- `resolvePlayerOutcome(...)`
- `findMatchResolutionTriggerId(...)`

## Project Structure

Main runtime logic lives in:

- [NexoriPublicApiDemoPlugin.java](D:/JanielNunez/hyjn-nexori/nexori-public-api-demo/src/main/java/io/github/hyjn/nexoridemo/NexoriPublicApiDemoPlugin.java)
- [MidCaptureService.java](D:/JanielNunez/hyjn-nexori/nexori-public-api-demo/src/main/java/io/github/hyjn/nexoridemo/midcapture/MidCaptureService.java)
- [MidCaptureHudService.java](D:/JanielNunez/hyjn-nexori/nexori-public-api-demo/src/main/java/io/github/hyjn/nexoridemo/midcapture/MidCaptureHudService.java)
- [MidCaptureTickSystem.java](D:/JanielNunez/hyjn-nexori/nexori-public-api-demo/src/main/java/io/github/hyjn/nexoridemo/midcapture/MidCaptureTickSystem.java)

## Build

Requirements:

- Java 25
- local Hytale install
- Nexori available on the target server

Compile:

```powershell
.\gradlew.bat compileJava
```

Build:

```powershell
.\gradlew.bat build
```

## Intended Audience

This repo is mainly for:

- reviewers who want to see how Nexori's public API is meant to be used
- modders who want a practical starting point for third-party match logic
- anyone validating that Nexori can power a custom minigame without exposing
  its internal runtime services
