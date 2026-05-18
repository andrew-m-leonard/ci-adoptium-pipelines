# BUILD_UID Test Guide

## Purpose

Test whether BUILD_UID can be reliably passed across stages and preserved during "Restart from Stage".

## Test File

[`TEST_BUILD_UID.Jenkinsfile`](TEST_BUILD_UID.Jenkinsfile)

## What This Tests

1. **Environment Variable Approach** (`env.BUILD_UID`)
   - Can `env.BUILD_UID` be set in Stage 1 and read in Stage 2 and 3?
   - Is `env.BUILD_UID` preserved when restarting from Stage 2 or 3?

2. **Stash/Unstash Approach** (file with UID)
   - Can we stash a file containing BUILD_UID in Stage 1?
   - Can we unstash it in Stage 2 and 3?
   - Is the stash preserved when restarting from Stage 2 or 3?

## How to Run the Test

### Step 1: Create Jenkins Job

1. Create new Pipeline job in Jenkins
2. Configure pipeline to use `TEST_BUILD_UID.Jenkinsfile`
3. Save

### Step 2: Test Normal Run

1. Click "Build Now"
2. Observe console output for all 3 stages
3. Look for:
   ```
   STAGE 1: Initialize
   🆕 Generated NEW BUILD_UID: 1704067200000-a1b2c3d4
   
   STAGE 2: Process
   ✅ env.BUILD_UID is available: 1704067200000-a1b2c3d4
   ✅ Unstashed build_uid.txt
   ✅ UIDs match!
   
   STAGE 3: Finalize
   ✅ env.BUILD_UID is available: 1704067200000-a1b2c3d4
   ✅ Unstashed build_uid.txt
   ✅ UIDs match!
   ```

**Expected Result:** Both approaches should work in normal run.

### Step 3: Test Restart from Stage 2

1. After build completes, click "Restart from Stage 2"
2. Observe console output
3. Look for:
   ```
   STAGE 2: Process
   📝 Test 1: Environment Variable
   ??? env.BUILD_UID is available: ???
   
   📦 Test 2: Unstash UID file
   ??? Unstashed build_uid.txt
   ```

**Questions to Answer:**
- ❓ Is `env.BUILD_UID` available in Stage 2 after restart?
- ❓ Can we unstash the UID file in Stage 2 after restart?
- ❓ Do the UIDs match?

### Step 4: Test Restart from Stage 3

1. After build completes, click "Restart from Stage 3"
2. Observe console output
3. Look for same indicators as Step 3

## Expected Behaviors

### Scenario A: Environment Variable Works

```
Normal Run (Build #100):
  Stage 1: env.BUILD_UID = "abc123"
  Stage 2: env.BUILD_UID = "abc123" ✅
  Stage 3: env.BUILD_UID = "abc123" ✅

Restart from Stage 2 (Build #101):
  Stage 2: env.BUILD_UID = "abc123" ✅ (from parameter)
  Stage 3: env.BUILD_UID = "abc123" ✅
```

**If this works:** Environment variable approach is viable!

### Scenario B: Environment Variable Doesn't Work

```
Normal Run (Build #100):
  Stage 1: env.BUILD_UID = "abc123"
  Stage 2: env.BUILD_UID = "abc123" ✅
  Stage 3: env.BUILD_UID = "abc123" ✅

Restart from Stage 2 (Build #101):
  Stage 2: env.BUILD_UID = null or "" ❌
  Stage 3: env.BUILD_UID = null or "" ❌
```

**If this happens:** Environment variable approach is NOT viable for restarts.

### Scenario C: Stash Works

```
Normal Run (Build #100):
  Stage 1: Stash "build-uid-stash"
  Stage 2: Unstash "build-uid-stash" ✅
  Stage 3: Unstash "build-uid-stash" ✅

Restart from Stage 2 (Build #101):
  Stage 2: Unstash "build-uid-stash" ✅ (from Build #100)
  Stage 3: Unstash "build-uid-stash" ✅
```

**If this works:** Stash approach is viable!

### Scenario D: Stash Doesn't Work

```
Normal Run (Build #100):
  Stage 1: Stash "build-uid-stash"
  Stage 2: Unstash "build-uid-stash" ✅
  Stage 3: Unstash "build-uid-stash" ✅

Restart from Stage 2 (Build #101):
  Stage 2: Unstash "build-uid-stash" ❌ (stash not found)
  Stage 3: Unstash "build-uid-stash" ❌
```

**If this happens:** Stash approach is NOT viable for restarts.

## Likely Outcomes

### Most Likely: Environment Variable via Parameter Works

The parameter approach should work because:
- Parameters are part of the build definition
- When you restart, Jenkins can pass the parameter value
- `env.BUILD_UID = params.BUILD_UID` should work in Stage 1

### Less Likely: Stash Works Across Restarts

Stashes are typically build-specific and may not survive restarts.

### Fallback: Use archiveArtifacts + copyArtifacts

If neither works, we must use:
```groovy
Stage 1:
  writeFile file: 'workspace/build_uid.txt', text: BUILD_UID
  archiveArtifacts 'workspace/**/*'

Stage 2:
  copyArtifacts(filter: 'workspace/build_uid.txt', ...)
  BUILD_UID = readFile('workspace/build_uid.txt')
```

## What to Report Back

After running the tests, report:

1. **Normal Run:**
   - ✅/❌ env.BUILD_UID available in all stages?
   - ✅/❌ Stash/unstash works in all stages?

2. **Restart from Stage 2:**
   - ✅/❌ env.BUILD_UID available?
   - ✅/❌ Stash/unstash works?
   - What was the actual BUILD_UID value?

3. **Restart from Stage 3:**
   - ✅/❌ env.BUILD_UID available?
   - ✅/❌ Stash/unstash works?
   - What was the actual BUILD_UID value?

## Console Output to Look For

### Success Indicators
```
✅ env.BUILD_UID is available: 1704067200000-a1b2c3d4
✅ Unstashed build_uid.txt
✅ UIDs match!
```

### Failure Indicators
```
❌ env.BUILD_UID is NOT available!
❌ Failed to unstash: ...
⚠️ UIDs don't match!
```

## Next Steps Based on Results

### If Environment Variable Works
→ Use the pattern in [`WORKSPACE_VALIDATION_PATTERN.md`](WORKSPACE_VALIDATION_PATTERN.md)

### If Stash Works
→ Modify pattern to use stash/unstash for BUILD_UID

### If Neither Works
→ Must use archiveArtifacts + copyArtifacts for BUILD_UID file
→ Update pattern to retrieve BUILD_UID from archived workspace

## Summary

This test will definitively answer whether:
1. Environment variables persist across stages and restarts
2. Stashes persist across stages and restarts
3. We need to use archiveArtifacts for BUILD_UID

**Run this test first before implementing the full workspace validation pattern!**