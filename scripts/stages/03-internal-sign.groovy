/**
 * Internal Sign Stage Script
 * 
 * Handles internal signing of JMOD files for Windows and Mac builds (JDK11+)
 * Uses Eclipse Foundation codesign service
 */

def call(Map config) {
    def buildConfig = config.buildConfig
    def base_path = config.base_path
    def repoHandler = config.repoHandler
    
    println "Starting internal signing for ${buildConfig.TARGET_OS}"
    
    // Safety check
    if (base_path == null || base_path.isEmpty()) {
        throw new Exception("[ERROR] base_path is null or empty - cannot proceed with signing")
    }
    
    // Clean any existing files
    sh "rm -rf ${base_path}/* || true"
    
    // Checkout build repository
    repoHandler.checkoutAdoptBuild(this)
    printGitRepoInfo()
    
    // Restore JMODs to be signed
    unstash 'jmods'
    
    def target_os = buildConfig.TARGET_OS
    
    // Sign JMOD files
    withEnv(["base_os=${target_os}", "base_path=${base_path}"]) {
        sh '''
            #!/bin/bash
            set -eu
            
            echo "Signing JMOD files under build path ${base_path} for base_os ${base_os}"
            TMP_DIR="${base_path}/"
            MAC_ENTITLEMENTS="$WORKSPACE/entitlements.plist"
            
            FILES=$(find "${TMP_DIR}" -type f)
            
            for f in $FILES
            do
                dir=$(dirname "$f")
                file=$(basename "$f")
                echo "Signing $f using Eclipse Foundation codesign service"
                
                # Rename to unsigned
                mv "$f" "${dir}/unsigned_${file}"
                
                success=false
                
                # Attempt signing
                if [ "${base_os}" == "mac" ]; then
                    if curl --fail --silent --show-error -o "$f" \
                           -F file="@${dir}/unsigned_${file}" \
                           -F entitlements="@$MAC_ENTITLEMENTS" \
                           https://cbi.eclipse.org/macos/codesign/sign; then
                        success=true
                    fi
                else
                    if curl --fail --silent --show-error -o "$f" \
                           -F file="@${dir}/unsigned_${file}" \
                           https://cbi.eclipse.org/authenticode/sign; then
                        success=true
                    fi
                fi
                
                # Retry logic if initial signing failed
                if [ $success == false ]; then
                    max_iterations=20
                    iteration=1
                    echo "Code Not Signed For File $f - retrying..."
                    
                    while [ $iteration -le $max_iterations ] && [ $success = false ]; do
                        echo "Retry attempt $iteration of $max_iterations"
                        sleep 1
                        
                        if [ "${base_os}" == "mac" ]; then
                            if curl --fail --silent --show-error -o "$f" \
                                   -F file="@${dir}/unsigned_${file}" \
                                   -F entitlements="@$MAC_ENTITLEMENTS" \
                                   https://cbi.eclipse.org/macos/codesign/sign; then
                                success=true
                            fi
                        else
                            if curl --fail --silent --show-error -o "$f" \
                                   -F file="@${dir}/unsigned_${file}" \
                                   https://cbi.eclipse.org/authenticode/sign; then
                                success=true
                            fi
                        fi
                        
                        if [ $success = false ]; then
                            echo "curl command failed, $f Failed Signing On Attempt $iteration"
                            iteration=$((iteration+1))
                            
                            if [ $iteration -gt $max_iterations ]; then
                                echo "Errors Encountered During Signing - max retries exceeded"
                                exit 1
                            fi
                        else
                            echo "$f Signed OK On Attempt $iteration"
                        fi
                    done
                fi
                
                # Restore permissions and cleanup
                chmod --reference="${dir}/unsigned_${file}" "$f"
                rm -rf "${dir}/unsigned_${file}"
            done
            
            echo "All JMOD files signed successfully"
        '''
    }
    
    // List signed files
    sh "ls -l ${base_path}/**/*"
    
    // Stash signed JMODs for assembly stage
    stash name: 'signed_jmods', includes: "${base_path}/**/*"
    
    println "Internal signing completed successfully"
    
    return config
}

return this

// Made with Bob
