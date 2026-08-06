/**
 * PipelineStages.groovy — canonical Jenkins stage ID list.
 *
 * Single definition of every stageId orchestrated by Jenkinsfile.declarative,
 * expressed as an ordered List<String>.  Consumed by:
 *
 *   - Jenkinsfile.launch  (collateAndMergeGroups → --orchestrated-stages)
 *   - SeedHelper.groovy   (generateJobs          → --orchestrated-stages)
 *
 * NOTE: Jenkinsfile.declarative cannot load this file because declarative
 * stage() names are evaluated at pipeline definition time — before any
 * script{} block runs.  The @Field final constants in Jenkinsfile.declarative
 * are therefore kept as-is and must be kept in sync with this list manually.
 * Every other Jenkins consumer should load this file rather than repeating
 * the list.
 *
 * Usage:
 *   def ps = load('ci/jenkins/lib/PipelineStages.groovy')   // from pipelines SCM root
 *   // OR (from seed job where pipelines are checked out under pipelines/)
 *   def ps = load('pipelines/ci/jenkins/lib/PipelineStages.groovy')
 *
 *   sh "collect-stage-params.py --orchestrated-stages ${ps.orchestratedStages()}"
 */

/**
 * Return a comma-separated string of all stage IDs orchestrated by Jenkins,
 * suitable for passing directly to collect-stage-params.py --orchestrated-stages.
 */
def orchestratedStages() {
    return JENKINS_STAGES.join(',')
}

/**
 * Return the ordered List<String> of all Jenkins-orchestrated stage IDs.
 * Matches the @Field final constants in Jenkinsfile.declarative exactly.
 */
def stageList() {
    return Collections.unmodifiableList(JENKINS_STAGES)
}

// ── Ordered stage ID list ────────────────────────────────────────────────────
// Must be kept in sync with the @Field final constants in Jenkinsfile.declarative
// and the "id" fields in scripts/stages/pipeline-stages.json.
@groovy.transform.Field
final List JENKINS_STAGES = [
    '01-initialize',
    '02-build',
    '03-internal-code-sign',
    '04-assemble-images',
    '06-post-build-code-sign',
    '07-installer',
    '08-code-sign-installer',
    '09-sbom-sign',
    '10-digital-artifact-sign',
    '11-verify-signing',
    '12-validate-sbom',
    '13-smoke-tests',
    '14-aqa-tests',
    '15-tck-tests',
    '16-publish',
    '20-reproducible-compare',
]

return this
