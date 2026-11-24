package com.maekom.codetracker.i18n

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

enum class Language(val code: String) {
    KO("KO"),
    EN("EN");

    companion object {
        fun fromCode(raw: String?): Language =
            if (raw.equals("EN", ignoreCase = true)) EN else KO
    }
}

/**
 * Centralized zero-restart KO/EN localization manager independent of IntelliJ language packs.
 * Persists selected language in PropertiesComponent and automatically cleans up listeners via Disposer.
 */
object CodeTrackerI18n {

    private const val PROP_KEY = "com.maekom.codetracker.language"

    @Volatile
    private var cachedLanguage: Language? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun currentLanguage(): Language {
        cachedLanguage?.let { return it }
        val loaded = runCatching {
            val saved = PropertiesComponent.getInstance().getValue(PROP_KEY, Language.KO.code)
            Language.fromCode(saved)
        }.getOrDefault(Language.KO)
        cachedLanguage = loaded
        return loaded
    }

    fun isEnglish(): Boolean = currentLanguage() == Language.EN

    fun languageCode(): String = currentLanguage().code

    fun tr(ko: String, en: String): String = if (isEnglish()) en else ko

    fun addListener(parentDisposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parentDisposable) {
            listeners.remove(listener)
        }
    }

    fun toggleLanguage(): Language {
        val next = if (currentLanguage() == Language.KO) Language.EN else Language.KO
        setLanguage(next)
        return next
    }

    fun setLanguage(language: Language) {
        cachedLanguage = language
        runCatching {
            PropertiesComponent.getInstance().setValue(PROP_KEY, language.code, Language.KO.code)
        }
        val notify = {
            for (listener in listeners) {
                runCatching { listener() }
            }
        }
        val app = ApplicationManager.getApplication()
        if (app == null || app.isDispatchThread) {
            notify()
        } else {
            app.invokeLater(notify)
        }
    }

    // ---- Common & Title Bar ----
    fun allOption(): String = tr("전체", "All")
    fun closeButton(): String = tr("닫기", "Close")
    fun langToggleTooltip(): String = tr("언어 전환 (KO / EN)", "Switch Language (KO / EN)")
    fun updateCheckTooltip(): String = tr("GitHub 최신 릴리즈 확인", "Check for latest GitHub release")

    // ---- Top Filter & Action Bar ----
    fun repoTooltip(): String = tr("대상 Git 저장소/모듈", "Target Git Repository / Module")
    fun branchTooltip(): String = tr("대상 브랜치", "Target Branch")
    fun dateRangeTooltip(): String = tr("조회 기간 (YYYY-MM-DD)", "Date Range (YYYY-MM-DD)")
    fun startDateTooltip(): String = tr("시작일 (YYYY-MM-DD)", "Start Date (YYYY-MM-DD)")
    fun endDateTooltip(): String = tr("종료일 (YYYY-MM-DD)", "End Date (YYYY-MM-DD)")
    fun authorTooltip(): String = tr("작성자 필터", "Author Filter")
    fun layerTooltip(): String = tr("아키텍처 레이어 필터", "Architecture Layer Filter")
    fun keywordEmptyText(): String = tr("클래스/테이블/API", "Class/Table/API")
    fun keywordTooltip(): String = tr("파일명, 클래스, 테이블, API 키워드 필터", "Filter by file, class, table, or API keyword")
    fun excludeMergesLabel(): String = tr("머지 제외", "No Merges")
    fun excludeMergesTooltip(): String = tr("머지 커밋 제외 (--no-merges)", "Exclude merge commits (--no-merges)")
    fun runButtonLabel(): String = tr("실행", "Run")
    fun runButtonTooltip(): String = tr("선택 조건으로 Git 워크플로우 분석 실행", "Run Git workflow analysis with current filters")
    fun copyMarkdownTooltip(): String = tr(
        "선택한 커밋의 아키텍처 및 수정 사유 요약을 마크다운으로 클립보드 복사",
        "Copy architecture & change rationale summary of selected commit as Markdown"
    )
    fun openDiffTooltip(): String = tr("선택한 파일의 표준 Diff 비교 창 열기", "Open standard Diff viewer for selected file")

    // ---- Panel Headers & Empty States ----
    fun timelinePanelTitle(): String = tr("  작업 에피소드 & 커밋 타임라인", "  Work Episodes & Commit Timeline")
    fun rationalePanelTitle(): String = tr("  수정 사유 & 아키텍처 체킹 패널", "  Change Reason & Architecture Check")
    fun pointerPanelTitle(): String = tr(
        "스마트 위치 포인터 (클릭: 코드 이동 / 우클릭: Diff 비교)",
        "Smart Code Pointers (Click: Jump / Right-click: Diff)"
    )
    fun initialSummaryText(): String = tr(
        "실행 버튼을 눌러 Git 커밋 및 워크플로우를 조회하세요.",
        "Click Run to analyze Git commits and workflows."
    )
    fun noGitRepoFound(): String = tr(
        "현재 프로젝트에서 Git 저장소를 찾지 못했습니다.",
        "No Git repositories found in this project."
    )
    fun timelineEmptyText(): String = tr("표시할 커밋이 없습니다.", "No commits to display.")
    fun pointerEmptyText(): String = tr(
        "커밋을 선택하면 레이어별 수정 위치 포인터가 표시됩니다.",
        "Select a commit to view layer-grouped code pointers."
    )
    fun rationaleInitialPlaceholder(): String = tr(
        "좌측 타임라인에서 커밋을 선택하면 수정 사유(Why), 연관 .md 문서, 아키텍처 체킹 배지가 표시됩니다.",
        "Select a commit on the left to view change rationale (Why), related .md docs, and architecture check badges."
    )
    fun rationaleNoCommitsPlaceholder(): String = tr(
        "조회된 커밋이 없습니다. 브랜치, 기간, 작성자 필터를 확인해 주세요.",
        "No commits found. Check branch, date range, and author filters."
    )
    fun rationaleSelectCommitPlaceholder(): String = tr(
        "좌측 타임라인에서 커밋을 선택하면 상세 Diff 분석 및 수정 사유가 표시됩니다.",
        "Select a commit from the timeline to inspect detailed Diff analysis and change rationale."
    )
    fun loadingCommitDetailText(): String = tr(
        "단일 커밋 Diff, 연관 .md 문서, 아키텍처 체킹 포인트를 백그라운드에서 분석 중입니다...",
        "Analyzing commit Diff, related .md docs, and architecture check points in background..."
    )

    // ---- Pointer Noise / Skip / Fallback ----
    fun importWhitespaceOnlyText(): String = tr("import/공백 정리만 포함됨", "Import/whitespace cleanup only")
    fun binarySkipText(): String = tr("바이너리 파일 (Diff 파싱 생략)", "Binary file (Diff skipped)")
    fun largeDiffSkipText(): String = tr("1MB 초과 대용량 Diff (파싱 생략)", "Diff exceeds 1MB (parsing skipped)")
    fun defaultSkipText(): String = tr("Diff 파싱 생략", "Diff parsing skipped")
    fun fallbackBlockContext(): String = tr("변경 블록", "Modified block")

    // ---- Context Menu Items ----
    fun menuFileHistory(): String = tr("이 파일의 기간 내 수정 이력 보기", "View File Change History in Period")
    fun menuDiffCompare(): String = tr("Before / After Diff 비교", "Compare Before / After Diff")
    fun menuJumpToCode(): String = tr("워크스페이스 코드 위치로 이동", "Jump to Code Location in Workspace")
    fun menuCopyCommitMarkdown(): String = tr("이 커밋 마크다운 복사", "Copy Commit Summary as Markdown")
    fun menuCommitFileDiff(): String = tr("이 커밋의 파일 Diff 비교", "Compare File Diff at This Commit")
    fun menuPointerDiff(): String = tr("이 변경 위치 Diff 비교", "Compare Diff at This Location")

    // ---- Summary / Counts ----
    fun commitsCountUnit(n: Int): String = tr("${n}커밋", "$n commits")
    fun filesCountUnit(n: Int): String = tr("${n}파일", "$n files")
    fun itemsCountUnit(n: Int): String = tr("${n}건", "$n")
    fun summaryStrip(
        totalCommits: Int,
        totalFiles: Int,
        truncated: Boolean,
        maxCommits: Int,
        layerRatio: String,
        stackPart: String
    ): String {
        val limitSuffix = if (truncated) tr(" [최대 ${maxCommits}커밋 제한]", " [Limit: $maxCommits commits]") else ""
        val base = tr(
            "총 커밋: ${totalCommits}건 | 수정 파일: ${totalFiles}건$limitSuffix",
            "Commits: $totalCommits | Modified files: $totalFiles$limitSuffix"
        )
        val layerSection = if (layerRatio.isNotBlank()) "  ||  $layerRatio" else ""
        val stackSection = if (stackPart.isNotBlank()) tr("  ||  스택: $stackPart", "  ||  Stacks: $stackPart") else ""
        return base + layerSection + stackSection
    }

    // ---- FileHistoryDialog ----
    fun fileHistoryDialogTitle(fileName: String): String =
        tr("파일 수정 이력 - $fileName", "File History - $fileName")
    fun fileHistoryScope(branch: String, startDate: String, endDate: String): String =
        tr("브랜치: $branch  |  기간: $startDate ~ $endDate", "Branch: $branch  |  Period: $startDate ~ $endDate")
    fun fileHistoryLoadingCount(): String = tr("이력 조회 중...", "Loading history...")
    fun fileHistoryFailedCount(): String = tr("조회 실패", "Load failed")
    fun fileHistoryTotalCount(totalCommits: Int, groupCount: Int, isTagBased: Boolean): String {
        val groupDesc = if (isTagBased) {
            tr("버전/태그 그룹 ${groupCount}개", "$groupCount version/tag groups")
        } else {
            tr("날짜 그룹 ${groupCount}개", "$groupCount date groups")
        }
        return tr("총 ${totalCommits}건 수정 ($groupDesc)", "Total $totalCommits changes ($groupDesc)")
    }
    fun fileHistoryLeftTitle(): String = tr("  버전(태그)별 파일 수정 이력", "  File History by Version / Tag")
    fun fileHistoryLoadingLeftText(): String = tr(
        "버전별 파일 변경 이력 및 태그 구간 분석 중...",
        "Analyzing file history and release tag ranges..."
    )
    fun fileHistoryLoadingRightText(): String = tr(
        "스마트 위치 포인터 및 변경 라인 분석 중...",
        "Analyzing smart code pointers and changed lines..."
    )
    fun fileHistoryEmptyText(): String = tr(
        "선택한 기간 및 조건 내 파일 수정 이력이 없습니다.",
        "No file modification history found for the selected period and criteria."
    )
    fun fileHistorySelectCommitHint(): String = tr(
        "하위 커밋을 선택하면 해당 시점의 수정 사유와 스마트 위치 포인터가 표시됩니다.",
        "Select a child commit to view its change rationale and smart code pointers."
    )
    fun fileHistoryNoTextHunks(statusLabel: String): String = tr(
        "텍스트 변경 블록 없음 ($statusLabel)",
        "No textual diff blocks ($statusLabel)"
    )

    // ---- Update Checker & Dialogs ----
    fun updateAvailableTitle(): String = tr("Maekom Code Tracker - 새 버전 안내", "Maekom Code Tracker - Update Available")
    fun updateAvailableMessage(currentVersion: String, latestVersion: String): String = tr(
        "새 버전의 Maekom Code Tracker가 출시되었습니다.\n\n버전: v$currentVersion → v$latestVersion\n\n확인을 누르면 IntelliJ Settings > Plugins 창이 열립니다. 목록에서 [Update] 버튼을 눌러 바로 업데이트하세요.",
        "A new version of Maekom Code Tracker is available.\n\nVersion: v$currentVersion → v$latestVersion\n\nClick OK to open IntelliJ Settings > Plugins, then click [Update] to install."
    )
    fun updateUpToDateTitle(): String = tr("Maekom Code Tracker - 업데이트 확인", "Maekom Code Tracker - Check for Updates")
    fun updateUpToDateMessage(currentVersion: String): String = tr(
        "현재 최신 버전(v$currentVersion)을 사용 중입니다.",
        "You are already using the latest version (v$currentVersion)."
    )
    fun updateRepoRegisteredTitle(): String = tr("Maekom Code Tracker - 업데이트 저장소 등록", "Maekom Code Tracker - Repository Registered")
    fun updateRepoRegisteredMessage(): String = tr(
        "Custom Plugin Repository가 등록되었습니다. 릴리즈 배포 후 Plugins 창에서 바로 업데이트할 수 있습니다.",
        "Custom Plugin Repository has been registered. You can update directly from Settings > Plugins once a release is published."
    )
    fun updateFailedTitle(): String = tr("Maekom Code Tracker - 업데이트 확인 실패", "Maekom Code Tracker - Update Check Failed")
    fun updateTimeoutMessage(seconds: Long): String = tr(
        "업데이트 확인 요청이 시간 초과(${seconds}초)되었습니다. 네트워크 연결을 확인해 주세요.",
        "Update check timed out after ${seconds}s. Please check your network connection."
    )
    fun updateOfflineMessage(): String = tr(
        "오프라인 상태이거나 GitHub 서버에 연결할 수 없습니다. (로컬 오프라인 분석 기능은 정상 사용 가능합니다.)",
        "Offline or unable to reach GitHub. (All local offline analysis features remain available.)"
    )
    fun updateConnectFailedMessage(): String = tr(
        "GitHub 서버에 연결할 수 없습니다. 사내 방화벽 또는 오프라인 환경을 확인해 주세요.",
        "Unable to connect to GitHub. Please check firewall or offline network settings."
    )
    fun updateHttpErrorMessage(statusCode: Int): String = tr(
        "최신 업데이트 메타데이터(updatePlugins.xml)를 조회하지 못했습니다 (HTTP $statusCode).",
        "Failed to fetch updatePlugins.xml metadata (HTTP $statusCode)."
    )
    fun updateXmlParseErrorMessage(): String = tr(
        "updatePlugins.xml 응답에서 플러그인 버전(version) 정보를 찾을 수 없습니다.",
        "Could not find plugin version attribute in updatePlugins.xml response."
    )
    fun updateUnexpectedErrorMessage(detail: String): String = tr(
        "업데이트 확인 중 오류가 발생했습니다: $detail",
        "An error occurred while checking for updates: $detail"
    )

    // ---- v0.2.0: Left Panel View Mode Toggle & 5-Stage Execution Flow ----
    fun viewModeTimeline(): String = tr("커밋/에피소드 타임라인", "Commit/Episode Timeline")
    fun viewModeExecutionFlow(): String = tr("실행 흐름도 재조립", "Execution Flow View")
    fun flowStage1(): String = tr(
        "1단계: 진입점 (Controller / Batch / Scheduler / Listener)",
        "Stage 1: Entrypoints (Controller / Batch / Scheduler / Listener)"
    )
    fun flowStage2(): String = tr(
        "2단계: 비즈니스 로직 (Service / Facade / Component)",
        "Stage 2: Business Logic (Service / Facade / Component)"
    )
    fun flowStage3(): String = tr(
        "3단계: 데이터 영속성 (Repository / JPA Entity / MyBatis Mapper & XML / QueryDSL)",
        "Stage 3: Data Persistence (Repository / JPA Entity / MyBatis Mapper & XML / QueryDSL)"
    )
    fun flowStage4(): String = tr(
        "4단계: 외부 시스템 연동 (FeignClient / WebClient / RestTemplate / Kafka / RabbitMQ / 외부 모듈)",
        "Stage 4: External Integration (FeignClient / WebClient / RestTemplate / Kafka / RabbitMQ / External)"
    )
    fun flowStage5(): String = tr(
        "5단계: 스키마·설정·모델 (DTO / VO / Config / application*.yml / SQL)",
        "Stage 5: Schema, Config & Model (DTO / VO / Config / application*.yml / SQL)"
    )
    fun flowModifiedBadge(count: Int, added: Int, deleted: Int): String = tr(
        "${count}회 수정 (+$added/-$deleted)",
        "$count edits (+$added/-$deleted)"
    )
    fun flowEmptyText(): String = tr(
        "실행 흐름도에 표시할 변경 파일이 없습니다.",
        "No modified files to assemble into execution flow."
    )
    fun flowLoadingMergedFile(fileName: String, commitCount: Int): String = tr(
        "'$fileName' 관련 ${commitCount}개 커밋의 변경 이력, 엔트리포인트, 위험 레이더를 병합 분석 중입니다...",
        "Aggregating Diff hunks, entrypoints, and risk radar across $commitCount commits for '$fileName'..."
    )
    fun flowMergedHeader(filePath: String, commitCount: Int): String = tr(
        "실행 흐름 병합 분석: $filePath (관련 커밋 ${commitCount}건)",
        "Merged Execution Flow Analysis: $filePath ($commitCount related commits)"
    )

    // ---- v0.2.0: Entrypoint Impact Tracer ----
    fun impactedEntrypointsTitle(): String = tr("영향받는 엔트리포인트 (클릭: 코드 점프)", "Impacted Entrypoints (Click to Jump)")
    fun entrypointDumbModeNotice(): String = tr(
        "IDE 인덱싱 진행 중(Dumb Mode)이어서 PSI 엔트리포인트 역추적을 생략했습니다. 인덱싱 완료 후 커밋을 다시 선택해 주세요.",
        "IDE indexing is in progress (Dumb Mode). Reselect the commit after indexing completes to trace PSI entrypoints."
    )
    fun entrypointNoneFound(): String = tr(
        "호출 그래프 상 연결된 Web API / Batch / 스케줄러 진입점이 탐지되지 않았습니다.",
        "No linked Web API, Batch, or Scheduler entrypoints detected in call graph."
    )
    fun entrypointTruncatedHint(): String = tr(
        "(탐색 한도 도달: 최대 6단계 / 150개 메서드)",
        "(Search limit reached: max depth 6 / 150 methods)"
    )

    // ---- v0.2.0: Side-Effect Risk Radar ----
    fun sideEffectRadarTitle(): String = tr(
        "백엔드 장애 위험 레이더 (클릭: 해당 라인 이동)",
        "Backend Side-Effect Risk Radar (Click to Jump)"
    )
    fun radarBadgeHigh(): String = tr("[위험 HIGH]", "[HIGH RISK]")
    fun radarBadgeWarn(): String = tr("[주의 WARN]", "[WARN]")
    fun radarRuleTxBoundary(): String = tr("트랜잭션 경계 변경", "Transaction Boundary Changed")
    fun radarRuleTxSelfInvocation(): String = tr("트랜잭션 자기 호출(Self-Invocation)", "Transaction Self-Invocation")
    fun radarRuleMyBatisBulk(): String = tr("MyBatis/DB 전체 갱신·삭제 위험", "MyBatis/DB Bulk Update/Delete Risk")
    fun radarRuleExternalSpec(): String = tr("외부 연동 및 스펙 파괴 위험", "External Integration / Spec Breaking Risk")
}
