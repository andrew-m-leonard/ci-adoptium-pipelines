#!/bin/bash
#
# Quick Start Script for Jenkins Automation
#
# This script sets up and starts a fully automated Jenkins instance
# with all jobs configured from code.
#
# Usage:
#   ./start-jenkins.sh [options]
#
# Options:
#   --password PASSWORD    Set admin password (default: admin)
#   --github-token TOKEN   Set GitHub token for private repos
#   --port PORT           Set Jenkins port (default: 8080)
#   --stop                Stop Jenkins
#   --restart             Restart Jenkins
#   --logs                Show Jenkins logs
#   --backup              Create backup
#   --restore FILE        Restore from backup

set -e

# Default values
JENKINS_ADMIN_PASSWORD="${JENKINS_ADMIN_PASSWORD:-admin}"
GITHUB_TOKEN="${GITHUB_TOKEN:-}"
JENKINS_PORT="${JENKINS_PORT:-8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_requirements() {
    log_info "Checking requirements..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi

    log_info "All requirements met"
}

start_jenkins() {
    log_info "Starting Jenkins..."

    cd "$SCRIPT_DIR"

    # Export environment variables
    export JENKINS_ADMIN_PASSWORD
    export GITHUB_TOKEN
    export JENKINS_PORT

    # Update docker-compose.yml port if needed
    if [ "$JENKINS_PORT" != "8080" ]; then
        log_info "Using custom port: $JENKINS_PORT"
        sed -i.bak "s/8080:8080/${JENKINS_PORT}:8080/" docker-compose.yml
    fi

    # Start containers
    docker-compose up -d

    log_info "Jenkins is starting..."
    log_info "Waiting for Jenkins to be ready..."

    # Wait for Jenkins to be ready
    local max_attempts=60
    local attempt=0
    while [ $attempt -lt $max_attempts ]; do
        if curl -s -o /dev/null -w "%{http_code}" http://localhost:${JENKINS_PORT}/login | grep -q "200"; then
            log_info "Jenkins is ready!"
            break
        fi
        attempt=$((attempt + 1))
        sleep 5
        echo -n "."
    done
    echo

    if [ $attempt -eq $max_attempts ]; then
        log_error "Jenkins failed to start within expected time"
        log_info "Check logs with: docker-compose logs jenkins"
        exit 1
    fi

    log_info "Jenkins is running at: http://localhost:${JENKINS_PORT}"
    log_info "Username: admin"
    log_info "Password: ${JENKINS_ADMIN_PASSWORD}"
    log_info ""
    log_info "Next steps:"
    log_info "1. Access Jenkins at http://localhost:${JENKINS_PORT}"
    log_info "2. The seed job should be created automatically"
    log_info "3. Run the seed job to create all pipeline jobs"
    log_info "4. Start building!"
}

stop_jenkins() {
    log_info "Stopping Jenkins..."
    cd "$SCRIPT_DIR"
    docker-compose down
    log_info "Jenkins stopped"
}

restart_jenkins() {
    log_info "Restarting Jenkins..."
    stop_jenkins
    sleep 2
    start_jenkins
}

show_logs() {
    log_info "Showing Jenkins logs (Ctrl+C to exit)..."
    cd "$SCRIPT_DIR"
    docker-compose logs -f jenkins
}

backup_jenkins() {
    log_info "Creating backup..."
    cd "$SCRIPT_DIR"

    local backup_dir="backups"
    mkdir -p "$backup_dir"

    local timestamp=$(date +%Y%m%d-%H%M%S)
    local backup_file="${backup_dir}/jenkins-backup-${timestamp}.tar.gz"

    docker-compose exec -T jenkins tar czf - /var/jenkins_home > "$backup_file"

    log_info "Backup created: $backup_file"
}

restore_jenkins() {
    local backup_file="$1"

    if [ ! -f "$backup_file" ]; then
        log_error "Backup file not found: $backup_file"
        exit 1
    fi

    log_warn "This will replace all Jenkins data with the backup"
    read -p "Are you sure? (yes/no): " confirm

    if [ "$confirm" != "yes" ]; then
        log_info "Restore cancelled"
        exit 0
    fi

    log_info "Restoring from backup: $backup_file"
    cd "$SCRIPT_DIR"

    # Stop Jenkins
    docker-compose down

    # Start Jenkins
    docker-compose up -d

    # Wait for Jenkins to start
    sleep 10

    # Restore backup
    cat "$backup_file" | docker-compose exec -T jenkins tar xzf - -C /

    # Restart Jenkins
    docker-compose restart jenkins

    log_info "Restore complete"
}

show_status() {
    log_info "Jenkins Status:"
    cd "$SCRIPT_DIR"
    docker-compose ps
}

show_help() {
    cat << EOF
Jenkins Automation Quick Start

Usage: $0 [options]

Options:
    --password PASSWORD    Set admin password (default: admin)
    --github-token TOKEN   Set GitHub token for private repos
    --port PORT           Set Jenkins port (default: 8080)
    --stop                Stop Jenkins
    --restart             Restart Jenkins
    --logs                Show Jenkins logs
    --backup              Create backup
    --restore FILE        Restore from backup
    --status              Show Jenkins status
    --help                Show this help message

Examples:
    # Start Jenkins with custom password
    $0 --password mySecurePassword

    # Start Jenkins on custom port
    $0 --port 9090

    # Stop Jenkins
    $0 --stop

    # View logs
    $0 --logs

    # Create backup
    $0 --backup

    # Restore from backup
    $0 --restore backups/jenkins-backup-20260617-120000.tar.gz

Environment Variables:
    JENKINS_ADMIN_PASSWORD    Admin password (default: admin)
    GITHUB_TOKEN             GitHub token for private repos
    JENKINS_PORT             Jenkins port (default: 8080)

EOF
}

# Parse arguments
ACTION="start"
while [[ $# -gt 0 ]]; do
    case $1 in
        --password)
            JENKINS_ADMIN_PASSWORD="$2"
            shift 2
            ;;
        --github-token)
            GITHUB_TOKEN="$2"
            shift 2
            ;;
        --port)
            JENKINS_PORT="$2"
            shift 2
            ;;
        --stop)
            ACTION="stop"
            shift
            ;;
        --restart)
            ACTION="restart"
            shift
            ;;
        --logs)
            ACTION="logs"
            shift
            ;;
        --backup)
            ACTION="backup"
            shift
            ;;
        --restore)
            ACTION="restore"
            RESTORE_FILE="$2"
            shift 2
            ;;
        --status)
            ACTION="status"
            shift
            ;;
        --help)
            show_help
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Execute action
check_requirements

case $ACTION in
    start)
        start_jenkins
        ;;
    stop)
        stop_jenkins
        ;;
    restart)
        restart_jenkins
        ;;
    logs)
        show_logs
        ;;
    backup)
        backup_jenkins
        ;;
    restore)
        restore_jenkins "$RESTORE_FILE"
        ;;
    status)
        show_status
        ;;
    *)
        log_error "Unknown action: $ACTION"
        exit 1
        ;;
esac

# Made with Bob
