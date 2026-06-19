# ==============================================================================
# WSO2 APIM CUSTOM WORKFLOW DEPLOYMENT SCRIPT
# ==============================================================================

# 1. Navigate to your Java project repository
Write-Host "Moving to Java Project Repository..." -ForegroundColor Cyan
cd "D:\Education\Programming\Java\wso2-usersignup-workflow"

# 2. Compile and package your custom Java code into a JAR bundle
Write-Host "Executing Maven clean install..." -ForegroundColor Cyan
mvn clean install

# Check if Maven build succeeded before continuing
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed! Stopping execution script."
    Exit
}

# 3. Copy the generated JAR into the WSO2 runtime dropins folder
Write-Host "Deploying artifact to WSO2 dropins engine..." -ForegroundColor Cyan
copy ".\target\com.mycompany.custom.usersignup.extension-1.0.0.jar" "C:\wso2am-4.2.0\repository\components\dropins\"

# 4. Navigate to the WSO2 executable binaries home folder
Write-Host "Moving to WSO2 Binaries Directory..." -ForegroundColor Cyan
cd "C:\wso2am-4.2.0\bin"

# 5. Start the API Manager core runtime engine, clearing old repository caches
Write-Host "Launching WSO2 API Manager with a clean repository cache..." -ForegroundColor Green
.\api-manager.bat -Dorg.wso2.carbon.clean.repository=true