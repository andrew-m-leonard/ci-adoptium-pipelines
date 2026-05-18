# GitHub EPICs and Issues for Pipeline Migration

This document provides ready-to-use templates for creating GitHub EPICs and issues to track the pipeline migration project.

---

## Executive Summary

### Why This Refactoring?

The current OpenJDK build pipeline uses a monolithic scripted pipeline architecture that has become increasingly difficult to maintain, debug, and extend. This refactoring addresses critical operational challenges:

**Current Pain Points:**
1. **No Restart Capability**: Pipeline failures require complete rebuilds from scratch, wasting hours of compute time and developer productivity
2. **Monolithic Design**: All logic embedded in a single large Groovy script makes changes risky and testing difficult
3. **Configuration Complexity**: Build configurations mixed with pipeline code makes it hard to add new platforms or variants
4. **Debugging Challenges**: When builds fail, identifying the root cause requires navigating thousands of lines of Groovy code
5. **Vendor Lock-in**: Heavy reliance on Jenkins-specific features and shared libraries limits portability
6. **Limited Reusability**: Other vendors cannot easily adapt the pipeline for their needs

**Benefits of New Architecture:**
1. **Stage-Level Restart**: Failed stages can be restarted individually, saving hours of rebuild time
2. **Modular Design**: Each stage is an independent script, making changes safer and testing easier
3. **Code/Config Separation**: Pipeline code (ci-adoptium-pipelines) separated from vendor configurations (ci-temurin-config)
4. **Better Debugging**: Clear stage boundaries and structured logging make troubleshooting straightforward
5. **CI-Agnostic**: 90% of code is portable shell scripts, reducing Jenkins dependency
6. **Vendor-Friendly**: Other vendors can use the pipeline code with their own configuration repositories
7. **Improved Reliability**: Declarative syntax with explicit stage dependencies reduces runtime errors
8. **Faster Iteration**: Developers can test individual stages locally without full Jenkins setup

**Business Impact:**
- **Reduced Downtime**: Restart capability eliminates hours of wasted rebuild time
- **Faster Releases**: Modular design enables parallel development and faster iteration
- **Lower Costs**: Efficient resource usage and reduced compute waste
- **Better Quality**: Easier testing and validation leads to more reliable builds
- **Vendor Adoption**: Other organizations can adopt Adoptium's build infrastructure

**Migration Strategy:**
- Gradual rollout with parallel execution (old and new pipelines run side-by-side)
- Byte-by-byte comparison ensures identical outputs
- Start with pilot platform (Linux x64 JDK21u) before expanding
- Zero downtime migration with fallback capability

---

## Architecture Overview

The new pipeline architecture separates code from configuration:

- **ci-adoptium-pipelines** (Code Repository)
  - Contains: Jenkinsfile.declarative, scripts/, tools/
  - Purpose: Pipeline execution code (CI-agnostic where possible)
  - Checked out via Jenkins SCM configuration

- **ci-temurin-config** (Configuration Repository)
  - Contains: configurations/ with JSON files
  - Purpose: Temurin-specific build configurations
  - Checked out via CONFIG_REPO_URL parameter

This separation allows:
- Vendors to maintain their own configuration repositories
- Shared pipeline code across all vendors
- Independent versioning of code and configurations
- Easier testing and validation

---

## EPIC 1: Foundation Infrastructure

**Title**: Foundation Infrastructure for Modular Pipeline

**Description**:
Establish the foundational infrastructure needed to support the new modular pipeline architecture with code/config separation. This includes creating both repositories, implementing conversion tools, testing framework, and comparison capabilities.

**Goals**:
- Create ci-adoptium-pipelines repository (code)
- Create ci-temurin-config repository (configurations)
- Implement configuration conversion tool (Groovy → JSON)
- Build comparison framework for validating builds
- Set up parallel execution infrastructure
- Update Jenkins jobs to use new SCM pattern

**Success Criteria**:
- [ ] Both repositories created and documented
- [ ] Config conversion tool working for all platforms
- [ ] All 20 JDK configurations converted to JSON
- [ ] Build comparison tool can detect differences
- [ ] Parallel execution jobs configured
- [ ] Jenkins jobs updated to use SCM checkout pattern

**Timeline**: Weeks 1-3
**Priority**: P0 (Blocker)
**Labels**: `epic`, `infrastructure`, `phase-1`

---

### Issue #1.1: Create Repository Structure

**Title**: Create ci-adoptium-pipelines and ci-temurin-config repositories

**Description**:
Create both repositories with proper structure to support code/config separation pattern.

**Tasks**:
- [ ] Create ci-adoptium-pipelines repository
  - [ ] Create `scripts/stages/` directory
  - [ ] Create `scripts/lib/` directory
  - [ ] Create `tools/` directory
  - [ ] Add Jenkinsfile.declarative
  - [ ] Add comprehensive README.md
  - [ ] Add CONTRIBUTING.md
  - [ ] Add CODE_CONFIG_SEPARATION.md
- [ ] Create ci-temurin-config repository
  - [ ] Create `configurations/` directory
  - [ ] Add README.md
  - [ ] Add .gitignore
- [ ] Set up proper file permissions (scripts executable)
- [ ] Configure branch protection rules

**Acceptance Criteria**:
- Both repositories created with proper structure
- All directories have README files
- File permissions correct (scripts executable)
- Documentation explains code/config separation
- Branch protection configured

**Estimated Effort**: 4 hours
**Priority**: P0
**Labels**: `infrastructure`, `setup`
**Assignee**: TBD

---

### Issue #1.2: Implement Config Conversion Tools

**Title**: Create Python-based Groovy to JSON configuration converter

**Description**:
Implement Python-based tools to convert existing Groovy pipeline configurations to the new JSON format. This includes both single-file and batch conversion capabilities.

**Tasks**:
- [ ] Create `convert-groovy-to-json.py` (single file converter)
  - [ ] Implement custom Groovy parser
  - [ ] Handle nested maps and lists
  - [ ] Extract variant-specific configurations
  - [ ] Format test arrays on single lines
  - [ ] Add blank lines between platform sections
- [ ] Create `convert-all-legacy-groovy-configs.py` (batch converter)
  - [ ] Add CLI argument parsing
  - [ ] Implement dry-run mode
  - [ ] Add progress reporting
  - [ ] Include error handling
- [ ] Convert all 20 JDK configurations (jdk8u through jdk27)
- [ ] Validate all generated JSON files
- [ ] Document usage with examples

**Acceptance Criteria**:
- Converts all 20 platform configs correctly
- Validates output JSON schema
- Handles edge cases gracefully
- Batch converter is user-friendly
- All configurations committed to ci-temurin-config
- Documentation complete with examples

**Estimated Effort**: 1 week
**Priority**: P0
**Labels**: `tooling`, `conversion`
**Assignee**: TBD

---

### Issue #1.3: Integrate Existing Build Comparison Tool

**Title**: Integrate repro_compare.sh for build validation

**Description**:
Integrate the existing reproducible build comparison tool (`temurin-build/tooling/reproducible/repro_compare.sh`) into the migration validation workflow. This tool is already proven and handles byte-by-byte comparison of JDK builds across Linux, macOS, and Windows platforms.

**Tasks**:
- [ ] Document repro_compare.sh usage for migration
- [ ] Create wrapper scripts for automated comparison
- [ ] Integrate into parallel execution Jenkins jobs
- [ ] Set up artifact archiving for comparison results
- [ ] Create dashboard for comparison metrics
- [ ] Add alerting for failed comparisons
- [ ] Document troubleshooting procedures

**Acceptance Criteria**:
- repro_compare.sh integrated into validation workflow
- Automated comparison runs after each parallel build
- Comparison results archived and accessible
- Dashboard shows comparison status and trends
- 100% reproducibility required for migration approval
- Documentation covers all platforms (Linux, macOS, Windows)

**Tool Capabilities**:
- Byte-by-byte binary comparison
- Platform-specific preprocessing (removes timestamps, build IDs)
- Supports Linux, macOS (Darwin), Windows (CYGWIN)
- Generates detailed diff reports
- Calculates reproducibility percentage
- Exit code indicates success/failure

**Estimated Effort**: 1 week
**Priority**: P0
**Labels**: `tooling`, `validation`, `integration`
**Assignee**: TBD

---

### Issue #1.4: Set Up Parallel Execution Jobs

**Title**: Configure Jenkins jobs for parallel pipeline execution

**Description**:
Set up Jenkins jobs that run both old and new pipelines in parallel for comparison purposes.

**Tasks**:
- [ ] Create parallel execution job template
- [ ] Configure artifact storage separation
- [ ] Set up build triggers
- [ ] Configure notifications
- [ ] Add comparison job integration
- [ ] Test with dummy builds

**Acceptance Criteria**:
- Both pipelines run in parallel
- Artifacts stored separately
- Comparison runs automatically
- Notifications work correctly
- No interference between pipelines

**Estimated Effort**: 3 days
**Priority**: P0
**Labels**: `jenkins`, `infrastructure`
**Assignee**: TBD

---

### Issue #1.5: Create Comparison Dashboard

**Title**: Build visual dashboard for build comparisons

**Description**:
Create a dashboard that displays comparison results between old and new pipeline builds.

**Tasks**:
- [ ] Design dashboard layout
- [ ] Implement data collection
- [ ] Create visualization components
- [ ] Add filtering capabilities
- [ ] Implement drill-down views
- [ ] Add export functionality
- [ ] Deploy dashboard

**Acceptance Criteria**:
- Shows comparison results clearly
- Updates in real-time
- Allows filtering by platform/version
- Provides detailed drill-down
- Accessible to team

**Estimated Effort**: 1 week
**Priority**: P1
**Labels**: `dashboard`, `visualization`
**Assignee**: TBD

---

### Issue #1.6: Document Infrastructure Setup

**Title**: Create comprehensive infrastructure documentation

**Description**:
Document all infrastructure components, setup procedures, and usage instructions.

**Tasks**:
- [ ] Document repository structure
- [ ] Create setup guide
- [ ] Document conversion tool usage
- [ ] Document comparison tool usage
- [ ] Create troubleshooting guide
- [ ] Add architecture diagrams
- [ ] Review and publish

**Acceptance Criteria**:
- All components documented
- Setup guide tested by new team member
- Troubleshooting guide covers common issues
- Diagrams are clear and accurate

**Estimated Effort**: 3 days
**Priority**: P1
**Labels**: `documentation`
**Assignee**: TBD

---

## EPIC 2: Pilot Execution

**Title**: Pilot Migration - Linux x64 JDK21u Temurin

**Description**:
Execute pilot migration for Linux x64 JDK21u Temurin platform. Run parallel builds, compare results, identify edge cases, and validate the new pipeline approach.

**Goals**:
- Successfully run 10+ parallel builds
- Achieve 100% artifact comparison success
- Identify and document all edge cases
- Validate performance is acceptable
- Build team confidence

**Success Criteria**:
- [ ] 10+ successful parallel builds
- [ ] All artifacts match exactly
- [ ] No performance regression
- [ ] Edge cases documented
- [ ] Team sign-off obtained

**Timeline**: Weeks 4-7
**Priority**: P0 (Blocker)
**Labels**: `epic`, `pilot`, `phase-2`

---

### Issue #2.1: Set Up Linux x64 JDK21u Pilot

**Title**: Configure pilot for Linux x64 JDK21u Temurin

**Description**:
Set up the pilot platform with all necessary configurations and infrastructure.

**Tasks**:
- [ ] Convert configuration to JSON
- [ ] Set up parallel execution job
- [ ] Configure artifact storage
- [ ] Set up monitoring
- [ ] Test dry run

**Acceptance Criteria**:
- Configuration converted successfully
- Parallel job configured
- Monitoring in place
- Dry run successful

**Estimated Effort**: 2 days
**Priority**: P0
**Labels**: `pilot`, `setup`
**Assignee**: TBD

---

### Issue #2.2: Configure Parallel Execution

**Title**: Enable parallel execution for pilot platform

**Description**:
Configure Jenkins to run both old and new pipelines in parallel for the pilot platform.

**Tasks**:
- [ ] Update Jenkins job configuration
- [ ] Configure build triggers
- [ ] Set up artifact separation
- [ ] Configure comparison automation
- [ ] Test parallel execution

**Acceptance Criteria**:
- Both pipelines run simultaneously
- No resource conflicts
- Artifacts stored separately
- Comparison runs automatically

**Estimated Effort**: 1 day
**Priority**: P0
**Labels**: `pilot`, `jenkins`
**Assignee**: TBD

---

### Issue #2.3: Run 10 Parallel Builds

**Title**: Execute 10 parallel builds for pilot validation

**Description**:
Run 10 parallel builds to gather data and validate the new pipeline.

**Tasks**:
- [ ] Trigger 10 parallel builds
- [ ] Monitor execution
- [ ] Collect comparison results
- [ ] Document any issues
- [ ] Analyze performance data

**Acceptance Criteria**:
- 10 builds completed
- All comparison data collected
- Issues documented
- Performance data analyzed

**Estimated Effort**: 1 week
**Priority**: P0
**Labels**: `pilot`, `testing`
**Assignee**: TBD

---

### Issue #2.4: Analyze Comparison Results

**Title**: Analyze pilot build comparison results

**Description**:
Thoroughly analyze comparison results from pilot builds to identify any differences or issues.

**Tasks**:
- [ ] Review all comparison reports
- [ ] Investigate any differences
- [ ] Categorize findings
- [ ] Determine root causes
- [ ] Create fix plan

**Acceptance Criteria**:
- All differences analyzed
- Root causes identified
- Findings categorized
- Fix plan created

**Estimated Effort**: 3 days
**Priority**: P0
**Labels**: `pilot`, `analysis`
**Assignee**: TBD

---

### Issue #2.5: Document Edge Cases Found

**Title**: Document all edge cases discovered during pilot

**Description**:
Create comprehensive documentation of all edge cases, quirks, and special handling discovered during pilot.

**Tasks**:
- [ ] List all edge cases found
- [ ] Document each case in detail
- [ ] Identify affected platforms
- [ ] Propose solutions
- [ ] Create edge case handling guide

**Acceptance Criteria**:
- All edge cases documented
- Solutions proposed
- Guide created
- Team reviewed

**Estimated Effort**: 2 days
**Priority**: P1
**Labels**: `pilot`, `documentation`
**Assignee**: TBD

---

### Issue #2.6: Fix Identified Issues

**Title**: Implement fixes for pilot issues

**Description**:
Implement fixes for all issues identified during pilot execution.

**Tasks**:
- [ ] Prioritize issues
- [ ] Implement fixes
- [ ] Test fixes locally
- [ ] Deploy fixes
- [ ] Verify fixes in pilot

**Acceptance Criteria**:
- All P0 issues fixed
- Fixes tested
- Fixes deployed
- Verification successful

**Estimated Effort**: 1 week
**Priority**: P0
**Labels**: `pilot`, `bugfix`
**Assignee**: TBD

---

### Issue #2.7: Validate Fixes with 10 More Builds

**Title**: Run validation builds after fixes

**Description**:
Run 10 more parallel builds to validate that fixes resolved all issues.

**Tasks**:
- [ ] Trigger 10 validation builds
- [ ] Monitor execution
- [ ] Verify fixes work
- [ ] Collect comparison results
- [ ] Confirm 100% success rate

**Acceptance Criteria**:
- 10 builds completed successfully
- All comparisons pass
- No new issues found
- 100% success rate achieved

**Estimated Effort**: 3 days
**Priority**: P0
**Labels**: `pilot`, `validation`
**Assignee**: TBD

---

### Issue #2.8: Create Edge Case Handling Guide

**Title**: Create comprehensive edge case handling guide

**Description**:
Create a guide for handling all discovered edge cases in future platform migrations.

**Tasks**:
- [ ] Compile all edge cases
- [ ] Document handling procedures
- [ ] Create decision tree
- [ ] Add examples
- [ ] Review with team

**Acceptance Criteria**:
- All edge cases covered
- Procedures clear
- Examples provided
- Team approved

**Estimated Effort**: 2 days
**Priority**: P1
**Labels**: `pilot`, `documentation`
**Assignee**: TBD

---

## EPIC 3: Tier 1 Rollout

**Title**: Tier 1 Platform Rollout (Linux x64 All Versions)

**Description**:
Expand migration to all Tier 1 platforms (Linux x64 for all JDK versions). These are the most common platforms with the highest usage.

**Platforms**:
- Linux x64 JDK21u Temurin ✓ (Pilot)
- Linux x64 JDK17u Temurin
- Linux x64 JDK11u Temurin
- Linux x64 JDK8u Temurin

**Success Criteria**:
- [ ] All Tier 1 platforms migrated
- [ ] 100% comparison success
- [ ] No performance regression
- [ ] Team confident with process

**Timeline**: Weeks 8-9
**Priority**: P0
**Labels**: `epic`, `tier-1`, `phase-3`

---

### Issue #3.1: Migrate Linux x64 JDK17u

**Title**: Migrate Linux x64 JDK17u Temurin to new pipeline

**Description**:
Migrate Linux x64 JDK17u Temurin following the established pilot process.

**Tasks**:
- [ ] Convert configuration
- [ ] Set up parallel execution
- [ ] Run 5 parallel builds
- [ ] Compare results
- [ ] Validate success
- [ ] Document findings

**Acceptance Criteria**:
- Configuration converted
- 5 builds successful
- All comparisons pass
- No issues found

**Estimated Effort**: 3 days
**Priority**: P0
**Labels**: `tier-1`, `migration`
**Assignee**: TBD

---

### Issue #3.2: Migrate Linux x64 JDK11u

**Title**: Migrate Linux x64 JDK11u Temurin to new pipeline

**Description**:
Migrate Linux x64 JDK11u Temurin following the established pilot process.

**Tasks**:
- [ ] Convert configuration
- [ ] Set up parallel execution
- [ ] Run 5 parallel builds
- [ ] Compare results
- [ ] Validate success
- [ ] Document findings

**Acceptance Criteria**:
- Configuration converted
- 5 builds successful
- All comparisons pass
- No issues found

**Estimated Effort**: 3 days
**Priority**: P0
**Labels**: `tier-1`, `migration`
**Assignee**: TBD

---

### Issue #3.3: Migrate Linux x64 JDK8u

**Title**: Migrate Linux x64 JDK8u Temurin to new pipeline

**Description**:
Migrate Linux x64 JDK8u Temurin following the established pilot process. Note: JDK8 may have unique requirements.

**Tasks**:
- [ ] Convert configuration
- [ ] Handle JDK8-specific requirements
- [ ] Set up parallel execution
- [ ] Run 5 parallel builds
- [ ] Compare results
- [ ] Validate success
- [ ] Document JDK8 quirks

**Acceptance Criteria**:
- Configuration converted
- JDK8 requirements handled
- 5 builds successful
- All comparisons pass
- Quirks documented

**Estimated Effort**: 4 days
**Priority**: P0
**Labels**: `tier-1`, `migration`, `jdk8`
**Assignee**: TBD

---

### Issue #3.4: Validate Tier 1 Platforms

**Title**: Final validation of all Tier 1 platforms

**Description**:
Perform final validation across all Tier 1 platforms to ensure consistency and reliability.

**Tasks**:
- [ ] Run simultaneous builds on all Tier 1
- [ ] Compare results across versions
- [ ] Validate performance metrics
- [ ] Check for any regressions
- [ ] Get team sign-off

**Acceptance Criteria**:
- All Tier 1 platforms validated
- No regressions found
- Performance acceptable
- Team sign-off obtained

**Estimated Effort**: 2 days
**Priority**: P0
**Labels**: `tier-1`, `validation`
**Assignee**: TBD

---

## Additional EPICs (Summary)

### EPIC 4: Tier 2 Rollout
- Mac aarch64 platforms
- Windows x64 platforms
- Timeline: Weeks 10-11

### EPIC 5: Tier 3 Rollout
- Linux aarch64 platforms
- Mac x64 platforms
- AIX platforms
- Linux s390x platforms
- Timeline: Weeks 12-13


### EPIC 7: Edge Cases
- Docker container builds
- Cross-compilation
- Custom build arguments
- Special signing
- Timeline: Weeks 16-17

### EPIC 8: Final Migration
- Complete all platforms
- Cutover execution
- Decommission old pipeline
- Timeline: Weeks 20-25

### EPIC 9: Documentation & Training
- User documentation
- Training materials
- Runbooks
- Timeline: Weeks 20-25

---

## Issue Template

Use this template for creating new migration issues:

```markdown
**Title**: [Platform] Migration to New Pipeline

**Description**:
Migrate [Platform/Version/Variant] to the new modular pipeline architecture.

**Platform Details**:
- OS: [Linux/Mac/Windows/AIX]
- Architecture: [x64/aarch64/ppc64/s390x]
- JDK Version: [jdk8u/jdk11u/jdk17u/jdk21u]
- Variant: Temurin

**Tasks**:
- [ ] Convert configuration to JSON
- [ ] Set up parallel execution job
- [ ] Run 5 parallel builds
- [ ] Compare all artifacts
- [ ] Validate test results
- [ ] Check performance metrics
- [ ] Document any edge cases
- [ ] Get team sign-off

**Acceptance Criteria**:
- [ ] Configuration converted successfully
- [ ] 5 builds completed successfully
- [ ] All artifact comparisons pass (100%)
- [ ] Test results match exactly
- [ ] Performance within acceptable range
- [ ] Edge cases documented
- [ ] Team sign-off obtained

**Estimated Effort**: 3-4 days
**Priority**: [P0/P1/P2]
**Labels**: `migration`, `[tier-1/tier-2/tier-3]`, `[platform]`
**Assignee**: TBD
**Milestone**: [Phase 3 - Gradual Rollout]

**Related Issues**: #[pilot-issue], #[edge-case-issues]
```

---

## Labels to Create

Create these labels in GitHub:

**Priority**:
- `P0` - Critical/Blocker (red)
- `P1` - High Priority (orange)
- `P2` - Medium Priority (yellow)
- `P3` - Low Priority (green)

**Phase**:
- `phase-1` - Foundation (blue)
- `phase-2` - Pilot (blue)
- `phase-3` - Gradual Rollout (blue)
- `phase-4` - Full Migration (blue)

**Type**:
- `epic` - Epic issue (purple)
- `infrastructure` - Infrastructure work (gray)
- `migration` - Platform migration (green)
- `tooling` - Tool development (cyan)
- `documentation` - Documentation (yellow)
- `validation` - Testing/validation (orange)
- `bugfix` - Bug fix (red)

**Platform**:
- `tier-1` - Tier 1 platform (gold)
- `tier-2` - Tier 2 platform (silver)
- `tier-3` - Tier 3 platform (bronze)
- `linux` - Linux platform (blue)
- `mac` - macOS platform (gray)
- `windows` - Windows platform (cyan)
- `aix` - AIX platform (brown)

**Component**:
- `jenkins` - Jenkins-specific (red)
- `dashboard` - Dashboard work (purple)
- `conversion` - Config conversion (green)
- `comparison` - Build comparison (orange)

---

## Milestone Structure

Create these milestones in GitHub:

1. **Phase 1: Foundation** (Weeks 1-3)
   - Due: [3 weeks from start]
   - Description: Establish infrastructure and tooling

2. **Phase 2: Pilot** (Weeks 4-7)
   - Due: [7 weeks from start]
   - Description: Validate approach with pilot platform

3. **Phase 3: Gradual Rollout** (Weeks 8-19)
   - Due: [19 weeks from start]
   - Description: Migrate all platforms incrementally

4. **Phase 4: Full Migration** (Weeks 20-25)
   - Due: [25 weeks from start]
   - Description: Complete migration and decommission old pipeline

---

## Project Board Setup

Create a GitHub Project board with these columns:

1. **Backlog** - Not yet started
2. **Ready** - Ready to work on
3. **In Progress** - Currently being worked on
4. **Review** - Awaiting review
5. **Testing** - In testing phase
6. **Done** - Completed

---

*Document Version: 1.0*
*Last Updated: 2026-05-12*