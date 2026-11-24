#!/usr/bin/env bash
set -euo pipefail

EXPECTED_REPO_DIR="$HOME/projects/maekom-code-tracker"
EXPECTED_REMOTE_SLUG="maekom-hub/maekom-code-tracker"
EXPECTED_REMOTE_URL="https://github.com/maekom-hub/maekom-code-tracker.git"

# Global option parsing before subcommand dispatch
FIX_REMOTE=false
POSITIONAL_ARGS=()
for arg in "$@"; do
    case "$arg" in
        --fix-remote)
            FIX_REMOTE=true
            ;;
        *)
            POSITIONAL_ARGS+=("$arg")
            ;;
    esac
done
set -- "${POSITIONAL_ARGS[@]+"${POSITIONAL_ARGS[@]}"}"

mask_url() {
    local url="${1:-}"
    if [[ -z "$url" ]]; then
        echo "(미설정)"
        return
    fi
    echo "$url" | sed -E 's#(https?://)[^/@]+@#\1***@#g'
}

guard_directory() {
    local current_dir
    current_dir="$(pwd -P)"
    local expected_dir
    if [[ -d "$EXPECTED_REPO_DIR" ]]; then
        expected_dir="$(cd "$EXPECTED_REPO_DIR" && pwd -P)"
    else
        expected_dir="$EXPECTED_REPO_DIR"
    fi

    if [[ "$current_dir" != "$expected_dir" ]]; then
        echo "[ERROR] 잘못된 작업 디렉터리입니다." >&2
        echo "  현재 경로: $current_dir" >&2
        echo "  필수 경로: $expected_dir" >&2
        echo "  '$EXPECTED_REPO_DIR' 디렉터리로 이동한 뒤 다시 실행해 주세요." >&2
        exit 1
    fi
}

guard_git_remote() {
    local is_git_repo=false
    local current_origin=""
    local needs_fix=false

    if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [[ -d ".git" ]]; then
        is_git_repo=true
        current_origin="$(git remote get-url origin 2>/dev/null || true)"
    fi

    if [[ "$is_git_repo" == false ]]; then
        echo "[WARN] 현재 디렉터리에 Git 저장소(.git)가 초기화되어 있지 않습니다." >&2
        needs_fix=true
    elif [[ -z "$current_origin" ]]; then
        echo "[WARN] Git 원격 저장소 'origin'이 설정되어 있지 않습니다." >&2
        echo "  필수 저장소 슬러그: $EXPECTED_REMOTE_SLUG" >&2
        needs_fix=true
    elif [[ "$current_origin" != *"$EXPECTED_REMOTE_SLUG"* ]]; then
        echo "[WARN] Git 원격 저장소 'origin'이 필수 저장소와 일치하지 않습니다!" >&2
        echo "  현재 origin URL: $(mask_url "$current_origin")" >&2
        echo "  필수 저장소 슬러그: $EXPECTED_REMOTE_SLUG" >&2
        needs_fix=true
    fi

    if [[ "$needs_fix" == true ]]; then
        local do_fix=false
        if [[ "$FIX_REMOTE" == true ]]; then
            do_fix=true
        elif [[ -t 0 ]]; then
            local answer=""
            read -r -p "원격 저장소를 '$(mask_url "$EXPECTED_REMOTE_URL")'로 자동 초기화/교정하시겠습니까? [y/N]: " answer
            if [[ "$answer" =~ ^[Yy]$ ]]; then
                do_fix=true
            fi
        fi

        if [[ "$do_fix" != true ]]; then
            echo "[ERROR] 저장소 검증에 실패하여 작업을 중단합니다." >&2
            echo "  자동 보정을 원하시면 '--fix-remote' 옵션을 추가해 실행해 주세요." >&2
            exit 1
        fi

        if [[ "$is_git_repo" == false ]]; then
            echo "[INFO] Git 저장소 초기화 중 (기본 브랜치: main)..."
            git init -b main
        fi

        if git remote get-url origin >/dev/null 2>&1; then
            echo "[INFO] origin URL을 '$(mask_url "$EXPECTED_REMOTE_URL")'로 교정 중..."
            git remote set-url origin "$EXPECTED_REMOTE_URL"
        else
            echo "[INFO] origin 원격 저장소 '$(mask_url "$EXPECTED_REMOTE_URL")' 추가 중..."
            git remote add origin "$EXPECTED_REMOTE_URL"
        fi

        local verified_origin
        verified_origin="$(git remote get-url origin 2>/dev/null || true)"
        if [[ "$verified_origin" != *"$EXPECTED_REMOTE_SLUG"* ]]; then
            echo "[ERROR] 원격 저장소 교정 후 재검증에 실패했습니다." >&2
            echo "  현재 origin URL: $(mask_url "$verified_origin")" >&2
            exit 1
        fi
        echo "[OK] 원격 저장소 교정 및 재검증 완료: $(mask_url "$verified_origin")"
    fi
}

get_current_version() {
    if [[ -f "build.gradle.kts" ]]; then
        sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' build.gradle.kts | head -n 1
    else
        echo "(unknown)"
    fi
}

update_readme_version_history() {
    local new_version="$1"
    local summary="$2"
    local release_date
    release_date="$(date +%Y-%m-%d)"
    local download_url="https://github.com/${EXPECTED_REMOTE_SLUG}/releases/download/v${new_version}/maekom-code-tracker-${new_version}.zip"
    local new_row="| \`v${new_version}\` | ${release_date} | ${summary} | [maekom-code-tracker-${new_version}.zip](${download_url}) |"

    if [[ ! -f "README.md" ]]; then
        echo "[ERROR] README.md 파일이 존재하지 않습니다." >&2
        exit 1
    fi

    if ! grep -q "<!-- VERSION_HISTORY_START -->" README.md || ! grep -q "<!-- VERSION_HISTORY_END -->" README.md; then
        echo "[ERROR] README.md에 VERSION_HISTORY 마커가 존재하지 않습니다." >&2
        exit 1
    fi

    local tmp_file
    tmp_file="$(mktemp)"
    awk -v new_row="$new_row" '
        BEGIN { in_block = 0; inserted = 0; }
        /<!-- VERSION_HISTORY_START -->/ { in_block = 1; print; next; }
        /<!-- VERSION_HISTORY_END -->/ { in_block = 0; print; next; }
        {
            print;
            if (in_block == 1 && inserted == 0 && $0 ~ /^\|[[:space:]]*---/) {
                print new_row;
                inserted = 1;
            }
        }
    ' README.md > "$tmp_file"
    mv "$tmp_file" README.md
    echo "[OK] README.md 버전 이력 테이블 갱신 완료: v${new_version}"
}

cmd_init() {
    guard_directory
    guard_git_remote
    local origin_url
    origin_url="$(git remote get-url origin 2>/dev/null || true)"
    local branch_name
    branch_name="$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse --short HEAD 2>/dev/null || echo '(unborn)')"
    echo "[OK] 저장소 초기화 및 검증 완료"
    echo "  작업 경로: $(pwd -P)"
    echo "  현재 브랜치: $branch_name"
    echo "  원격 origin: $(mask_url "$origin_url")"
}

cmd_build() {
    guard_directory
    if [[ ! -x "./gradlew" ]]; then
        echo "[ERROR] ./gradlew 실행 파일을 찾을 수 없습니다." >&2
        exit 1
    fi
    echo "[INFO] ./gradlew buildPlugin 실행 중..."
    ./gradlew buildPlugin

    echo ""
    echo "[OK] 빌드 완료 - 생성된 배포 아카이브:"
    local found=false
    for zip_file in build/distributions/maekom-code-tracker-*.zip; do
        if [[ -f "$zip_file" ]]; then
            found=true
            local abs_path
            abs_path="$(pwd -P)/$zip_file"
            local size_human
            size_human="$(du -h "$zip_file" | awk '{print $1}')"
            local size_bytes
            size_bytes="$(wc -c < "$zip_file" | tr -d ' ')"
            echo "  경로: $abs_path"
            echo "  용량: $size_human (${size_bytes} bytes)"
        fi
    done
    if [[ "$found" == false ]]; then
        echo "[WARN] build/distributions/maekom-code-tracker-*.zip 아카이브를 찾지 못했습니다." >&2
        exit 1
    fi
}

cmd_release() {
    guard_directory
    guard_git_remote

    local raw_version="${1:-}"
    local summary="${2:-Maekom Code Tracker v${raw_version#v} 릴리즈}"

    if [[ -z "$raw_version" ]]; then
        echo "[ERROR] 릴리즈 버전을 입력해 주세요. 예: ./manage.sh release 0.1.1 \"버그 수정\"" >&2
        exit 1
    fi

    local new_version="${raw_version#v}"
    if [[ ! "$new_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
        echo "[ERROR] 유효하지 않은 버전 형식입니다: $raw_version (예: 0.1.1)" >&2
        exit 1
    fi

    if git rev-parse "v${new_version}" >/dev/null 2>&1; then
        echo "[ERROR] 태그 'v${new_version}'가 이미 존재합니다." >&2
        exit 1
    fi

    echo "[INFO] build.gradle.kts 버전 갱신 -> ${new_version}"
    local tmp_gradle
    tmp_gradle="$(mktemp)"
    sed -E "s/^version[[:space:]]*=[[:space:]]*\"[^\"]+\"/version = \"${new_version}\"/" build.gradle.kts > "$tmp_gradle"
    mv "$tmp_gradle" build.gradle.kts

    update_readme_version_history "$new_version" "$summary"

    echo "[INFO] Git 스테이징 및 커밋/태그/푸시 수행 중..."
    git add .
    if ! git diff --cached --quiet; then
        git commit -m "chore: release v${new_version}"
    else
        echo "[INFO] 스테이징된 변경 사항이 없어 커밋을 건너뜁니다."
    fi

    git push -u origin HEAD
    git tag "v${new_version}"
    git push origin "v${new_version}"

    echo "[OK] v${new_version} 릴리즈 완료!"
}

cmd_status() {
    guard_directory

    local current_version
    current_version="$(get_current_version)"
    local origin_url=""
    local branch_name="(Git 미초기화)"

    if git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [[ -d ".git" ]]; then
        origin_url="$(git remote get-url origin 2>/dev/null || true)"
        branch_name="$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse --short HEAD 2>/dev/null || echo '(커밋 없음)')"
    fi

    echo "============================================================"
    echo " Maekom Code Tracker 프로젝트 상태"
    echo "============================================================"
    echo " 작업 경로       : $(pwd -P)"
    echo " 현재 플러그인 버전 : ${current_version}"
    echo " 원격 origin URL : $(mask_url "$origin_url")"
    echo " 현재 브랜치     : ${branch_name}"
    echo ""
    echo "[최근 Git 태그 (최대 10개)]"
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        local tags
        tags="$(git tag --sort=-creatordate 2>/dev/null | head -n 10 || true)"
        if [[ -n "$tags" ]]; then
            echo "$tags" | sed 's/^/  - /'
        else
            echo "  (태그 없음)"
        fi
    else
        echo "  (Git 저장소 아님)"
    fi

    echo ""
    echo "[최근 Git 커밋 (최대 10개)]"
    if git rev-parse --verify HEAD >/dev/null 2>&1; then
        git log -n 10 --pretty=format:"  %h %ad %an - %s" --date=short
        echo ""
    else
        echo "  (커밋 이력 없음)"
    fi
    echo "============================================================"
}

cmd_rollback() {
    guard_directory
    guard_git_remote

    if ! git rev-parse --verify HEAD >/dev/null 2>&1; then
        echo "[ERROR] 롤백할 Git 커밋 이력이 없습니다." >&2
        exit 1
    fi

    echo "[최근 Git 태그]"
    local tags
    tags="$(git tag --sort=-creatordate | head -n 10 || true)"
    if [[ -n "$tags" ]]; then
        echo "$tags" | sed 's/^/  - /'
    else
        echo "  (태그 없음)"
    fi

    echo ""
    echo "[최근 Git 커밋]"
    git log -n 10 --oneline | sed 's/^/  /'
    echo ""

    local target_ref="${1:-}"
    if [[ -z "$target_ref" ]]; then
        if [[ ! -t 0 ]]; then
            echo "[ERROR] 비대화형 모드에서는 롤백 대상 태그 또는 커밋 해시를 인자로 전달해야 합니다." >&2
            exit 1
        fi
        read -r -p "복구할 태그명 또는 커밋 해시를 입력하세요: " target_ref
    fi

    if [[ -z "$target_ref" ]] || ! git rev-parse --verify "${target_ref}^{commit}" >/dev/null 2>&1; then
        echo "[ERROR] 유효하지 않은 Git 참조입니다: $target_ref" >&2
        exit 1
    fi

    local backup_branch="backup-before-rollback-$(date +%Y%m%d-%H%M%S)"
    echo "[INFO] 안전 백업 브랜치 생성: $backup_branch"
    git branch "$backup_branch"

    echo "[INFO] '${target_ref}' 상태로 복구 중..."
    git reset --hard "$target_ref"

    echo "[INFO] 복구된 상태에서 플러그인 재빌드 실행..."
    cmd_build

    echo "[OK] 롤백 및 재빌드 완료 (백업 브랜치: $backup_branch)"
}

cmd_clean() {
    guard_directory
    if [[ -x "./gradlew" ]]; then
        echo "[INFO] ./gradlew clean 실행 중..."
        ./gradlew clean
    fi
    echo "[INFO] 임시 빌드 폴더 정리 중 (build, out, .kotlin)..."
    rm -rf build out .kotlin
    echo "[OK] 정리 완료"
}

usage() {
    cat <<EOF
사용법: ./manage.sh <subcommand> [options] [--fix-remote]

서브커맨드:
  init [--fix-remote]                  Git 저장소 및 origin 원격 저장소 초기화·검증·교정
  build                                ./gradlew buildPlugin 실행 및 배포 ZIP 경로·용량 출력
  release <버전> [요약] [--fix-remote]  버전 갱신, README 이력 추가, 커밋/푸시/태그 배포
  status                               플러그인 버전, 원격 URL(마스킹), 브랜치, 태그, 커밋 확인
  rollback [참조] [--fix-remote]        백업 브랜치 생성 후 선택 태그/커밋으로 복구 및 재빌드
  clean                                ./gradlew clean 및 임시 빌드 디렉터리 정리
EOF
}

main() {
    local subcmd="${1:-}"
    if [[ -z "$subcmd" ]]; then
        usage
        exit 1
    fi
    shift || true

    case "$subcmd" in
        init)
            cmd_init "$@"
            ;;
        build)
            cmd_build "$@"
            ;;
        release)
            cmd_release "$@"
            ;;
        status)
            cmd_status "$@"
            ;;
        rollback)
            cmd_rollback "$@"
            ;;
        clean)
            cmd_clean "$@"
            ;;
        -h|--help|help)
            usage
            ;;
        *)
            echo "[ERROR] 알 수 없는 서브커맨드입니다: $subcmd" >&2
            usage
            exit 1
            ;;
    esac
}

main "$@"
