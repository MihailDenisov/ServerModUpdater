package dev.servermodupdates;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(value = "modupdates", dist = Dist.DEDICATED_SERVER)
public final class ServerModUpdates {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PAGE_SIZE = 6;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "modupdates-worker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Future<?> currentTask;
    private volatile Report lastReport;
    private volatile UpdatePlan currentPlan;
    private volatile String operation = "IDLE";
    private volatile List<JarUpdateService.Result> updateResults = List.of();
    private volatile boolean reminderOutstanding;
    private volatile boolean reminderAutoModpackOutstanding;
    private final AtomicBoolean planCancelRequested = new AtomicBoolean();
    private volatile List<PlanExecutor.Action> planActions = List.of();
    private volatile Path lastPlanResult;

    public ServerModUpdates(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        Settings settings = initializeSettings();
        JarUpdateService.Recovery recovery = JarUpdateService.recoverPendingDetailed(gameDir(), settings.enableAutoModpack());
        if (recovery.changed()) {
            reminderOutstanding = true;
            reminderAutoModpackOutstanding = recovery.autoModpackChanged();
            LOGGER.warn(Messages.restart(settings, recovery.autoModpackChanged()));
        }
    }

    private void onCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("modupdates").requires(source -> source.hasPermission(CommandPolicy.REQUIRED_PERMISSION_LEVEL));
        root.then(Commands.literal("check").executes(context -> check(context.getSource())));
        root.then(Commands.literal("status").executes(context -> status(context.getSource())));
        root.then(Commands.literal("list")
                .executes(context -> list(context.getSource(), 1, false))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "page"), false))));
        var update = Commands.literal("update");
        update.then(Commands.literal("list")
                .executes(context -> list(context.getSource(), 1, true))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "page"), true))));
        update.then(Commands.literal("status").executes(context -> updateStatus(context.getSource())));
        update.then(Commands.literal("all")
                .then(Commands.literal("dryrun").executes(context -> dryRun(context.getSource())))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(context -> bulk(context.getSource(), StringArgumentType.getString(context, "token"))))));
        update.then(Commands.argument("id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(planIds(), builder))
                .then(Commands.argument("provider", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("modrinth", "curseforge"), builder))
                        .executes(context -> updateOne(context.getSource(), StringArgumentType.getString(context, "id"),
                                StringArgumentType.getString(context, "provider")))));
        root.then(update);
        root.then(Commands.literal("rollback").then(Commands.argument("id", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(planIds(), builder))
                .executes(context -> rollback(context.getSource(), StringArgumentType.getString(context, "id")))));
        root.then(Commands.literal("diagnose").then(Commands.literal("curseforge")
                .executes(context -> diagnoseCurseForge(context.getSource()))));
        var plan = Commands.literal("plan");
        plan.then(Commands.literal("list")
                .executes(context -> planList(context.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> planList(context.getSource(), IntegerArgumentType.getInteger(context, "page")))));
        plan.then(Commands.literal("preview")
                .executes(context -> planPreview(context.getSource(), null))
                .then(Commands.argument("planId", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(markdownPlanIds(), builder))
                        .executes(context -> planPreview(context.getSource(), StringArgumentType.getString(context, "planId")))));
        plan.then(Commands.literal("apply")
                .executes(context -> planApply(context.getSource(), null))
                .then(Commands.argument("planId", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(markdownPlanIds(), builder))
                        .executes(context -> planApply(context.getSource(), StringArgumentType.getString(context, "planId")))));
        plan.then(Commands.literal("status").executes(context -> planStatus(context.getSource())));
        plan.then(Commands.literal("cancel").executes(context -> planCancel(context.getSource())));
        root.then(plan);
        root.then(Commands.literal("configpath").executes(context -> configPath(context.getSource())));
        root.then(Commands.literal("help").executes(context -> help(context.getSource())));
        event.getDispatcher().register(root);
    }

    private int check(CommandSourceStack source) {
        if (!begin(source, "CHECK")) return 0;
        Settings initial = settings();
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(initial,
                "Scan started; files will not be changed.", "Проверка запущена; файлы не изменяются.")), false);
        Path gameDir = gameDir();
        currentTask = worker.submit(() -> {
            try {
                Settings settings = Settings.load(FMLPaths.CONFIGDIR.get()).settings();
                Report report = new UpdateChecker(settings).check(gameDir, settings);
                if (Settings.load(FMLPaths.CONFIGDIR.get()).languageFallback()) {
                    report.warnings.add("Unsupported language value; using en_us.");
                    LOGGER.warn("Unsupported modupdates language value; falling back to en_us");
                }
                ReportWriter.Written written = ReportWriter.write(gameDir, report, settings);
                MarkdownPlanRepository.Written plan = new MarkdownPlanRepository(gameDir).create(report, written, settings);
                lastReport = report;
                currentPlan = UpdatePlan.from(report);
                operation = "CHECK_COMPLETED";
                send(source, "[ModUpdates] " + Messages.text(settings, "Done: files ", "Готово: файлов ") + report.scanned.size()
                        + Messages.text(settings, ", with updates ", ", с обновлениями ") + report.filesWithUpdates()
                        + Messages.text(settings, ", unmatched/unverified ", ", неопознано/не проверено ")
                        + report.unmatchedCount() + ". Modrinth: " + report.providerSummary(Report.Provider.MODRINTH, settings.language())
                        + "; CurseForge: " + report.providerSummary(Report.Provider.CURSEFORGE, settings.language())
                        + "; report=" + written.markdown().getFileName() + "; plan="
                        + plan.markdown().getFileName() + "; planId=" + plan.planId() + ".");
            } catch (Exception e) {
                operation = "CHECK_FAILED: " + e.getClass().getSimpleName();
                LOGGER.error("ModUpdates scan failed", e);
                fail(source, "[ModUpdates] " + Messages.text(initial, "Scan failed: ", "Ошибка проверки: ")
                        + e.getClass().getSimpleName() + Messages.text(initial, ". See server logs.", ". См. server/logs."));
            } finally {
                running.set(false);
                currentTask = null;
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSourceStack source) {
        Settings settings = settings();
        Report report = lastReport;
        String scan = report == null ? Messages.text(settings, "no completed scan", "нет завершённой проверки")
                : report.time + Messages.text(settings, ", files=", ", файлов=") + report.scanned.size()
                + ", updates=" + report.filesWithUpdates() + ", unresolved=" + report.unresolvedCount();
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(settings, "Scan: ", "Проверка: ")
                + scan + Messages.text(settings, "; operation=", "; операция=") + operation
                + Messages.text(settings, "; active=", "; активна=") + running.get()), false);
        if (report != null) source.sendSuccess(() -> Component.literal("[ModUpdates] Modrinth: "
                + report.providerSummary(Report.Provider.MODRINTH, settings.language()) + "; CurseForge: "
                + report.providerSummary(Report.Provider.CURSEFORGE, settings.language())), false);
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSourceStack source, int page, boolean actionableOnly) {
        Settings settings = settings();
        UpdatePlan plan = currentPlan;
        if (plan == null) return noPlan(source);
        List<UpdatePlan.Item> items = actionableOnly ? plan.actionable() : plan.items();
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > pages) {
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "Page is outside range 1..", "Страница вне диапазона 1..") + pages));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + (actionableOnly
                ? Messages.text(settings, "Actionable updates", "Доступные обновления")
                : Messages.text(settings, "Results", "Результаты"))
                + Messages.text(settings, " page ", " страница ") + page + "/" + pages + "; scan=" + plan.scanTime()), false);
        int from = (page - 1) * PAGE_SIZE;
        for (UpdatePlan.Item item : items.subList(from, Math.min(items.size(), from + PAGE_SIZE))) {
            String providers = item.providers().entrySet().stream().map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT)
                    + "=" + entry.getValue().version() + " " + entry.getValue().pageUrl()).reduce((a, b) -> a + "; " + b)
                    .orElse("UNMATCHED / UNVERIFIED");
            source.sendSuccess(() -> Component.literal(item.id() + " | " + item.installed().relative() + " | " + providers), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int dryRun(CommandSourceStack source) {
        Settings settings = settings();
        UpdatePlan plan = currentPlan;
        if (plan == null) return noPlan(source);
        if (running.get()) return busy(source);
        List<UpdatePlan.Item> items = plan.actionable();
        source.sendSuccess(() -> Component.literal("[ModUpdates] DRY RUN: " + Messages.text(settings,
                "would replace ", "будет заменено ") + items.size() + Messages.text(settings, "; excluded ", "; исключено ")
                + (plan.items().size() - items.size()) + Messages.text(settings, ". No files changed.", ". Файлы не изменены.")), false);
        for (UpdatePlan.Item item : items) {
            Report.Provider provider = selectBulkProvider(item);
            Report.Download download = item.providers().get(provider);
            source.sendSuccess(() -> Component.literal(item.id() + " | " + item.installed().relative() + " -> "
                    + download.fileName() + " via " + provider.name().toLowerCase(Locale.ROOT)), false);
        }
        UpdatePlan.Confirmation confirmation = plan.issueConfirmation(Instant.now());
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(settings,
                "To apply: ", "Для применения: ") + "/modupdates update all confirm "
                + confirmation.token() + Messages.text(settings, " (valid for 5 minutes and one use)",
                " (действует 5 минут и только один раз)")), false);
        return Command.SINGLE_SUCCESS;
    }

    private int bulk(CommandSourceStack source, String token) {
        UpdatePlan plan = currentPlan;
        if (plan == null) return noPlan(source);
        if (!plan.consumeConfirmation(token, Instant.now())) {
            Settings settings = settings();
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "Invalid or expired token; run dryrun again.", "Неверный или просроченный token; снова выполните dryrun.")));
            return 0;
        }
        if (!begin(source, "UPDATE_ALL")) return 0;
        return submitUpdate(source, plan.actionable().stream().map(item -> new Selection(item, selectBulkProvider(item))).toList());
    }

    private int updateOne(CommandSourceStack source, String id, String providerName) {
        UpdatePlan plan = currentPlan;
        if (plan == null) return noPlan(source);
        UpdatePlan.Item item = plan.find(id).orElse(null);
        if (item == null) return unknownId(source);
        Report.Provider provider = parseProvider(providerName);
        if (provider == null || item.provider(provider).isEmpty()) {
            Settings settings = settings();
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "No verified actionable file from that provider for this ID.",
                    "Для этого ID нет проверенного actionable-файла выбранного провайдера.")));
            return 0;
        }
        if (!begin(source, "UPDATE_ONE " + item.id())) return 0;
        return submitUpdate(source, List.of(new Selection(item, provider)));
    }

    private int submitUpdate(CommandSourceStack source, List<Selection> selections) {
        Settings initial = settings();
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(initial,
                "Operation started; no automatic restart or hot reload will occur.",
                "Операция запущена; автоматического restart/hot reload не будет.")), false);
        currentTask = worker.submit(() -> {
            List<JarUpdateService.Result> results = new ArrayList<>();
            try {
                Settings settings = Settings.load(FMLPaths.CONFIGDIR.get()).settings();
                JarUpdateService service = new JarUpdateService(gameDir(), settings);
                for (Selection selection : selections) {
                    JarUpdateService.Result result = service.apply(selection.item(), selection.provider());
                    results.add(result);
                    send(source, "[ModUpdates] " + result.id() + ": " + (result.success() ? "OK: " : "FAIL: ") + result.message());
                }
                updateResults = List.copyOf(results);
                boolean changed = results.stream().anyMatch(JarUpdateService.Result::changed);
                reminderOutstanding |= changed;
                reminderAutoModpackOutstanding |= results.stream().anyMatch(result -> result.changed()
                        && isAutoModpackPath(result.path()));
                operation = results.stream().allMatch(JarUpdateService.Result::success) ? "UPDATE_COMPLETED" : "UPDATE_PARTIAL_OR_FAILED";
                if (changed) reminder(source, settings, results.stream().anyMatch(result -> result.changed()
                        && isAutoModpackPath(result.path())));
            } catch (Exception e) {
                operation = "UPDATE_FAILED: " + e.getClass().getSimpleName();
                LOGGER.error("ModUpdates update job failed", e);
                fail(source, "[ModUpdates] " + Messages.text(initial, "Operation failed: ", "Ошибка операции: ")
                        + e.getClass().getSimpleName());
            } finally {
                running.set(false);
                currentTask = null;
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int rollback(CommandSourceStack source, String id) {
        UpdatePlan plan = currentPlan;
        if (plan == null) return noPlan(source);
        UpdatePlan.Item item = plan.find(id).orElse(null);
        if (item == null) return unknownId(source);
        if (!begin(source, "ROLLBACK " + item.id())) return 0;
        currentTask = worker.submit(() -> {
            try {
                Settings settings = Settings.load(FMLPaths.CONFIGDIR.get()).settings();
                JarUpdateService.Result result = new JarUpdateService(gameDir(), settings).rollback(item);
                updateResults = List.of(result);
                operation = result.success() ? "ROLLBACK_COMPLETED" : "ROLLBACK_FAILED";
                send(source, "[ModUpdates] " + result.id() + ": " + (result.success() ? "OK: " : "FAIL: ") + result.message());
                if (result.changed()) {
                    reminderOutstanding = true;
                    reminderAutoModpackOutstanding |= isAutoModpackPath(result.path());
                    reminder(source, settings, isAutoModpackPath(result.path()));
                }
            } catch (Exception e) {
                operation = "ROLLBACK_FAILED: " + e.getClass().getSimpleName();
                fail(source, "[ModUpdates] " + Messages.text(settings(), "Rollback failed: ", "Ошибка rollback: ")
                        + e.getClass().getSimpleName());
            } finally {
                running.set(false);
                currentTask = null;
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int updateStatus(CommandSourceStack source) {
        Settings settings = settings();
        source.sendSuccess(() -> Component.literal("[ModUpdates] operation=" + operation + "; active=" + running.get()
                + "; restartReminder=" + reminderOutstanding), false);
        for (JarUpdateService.Result result : updateResults) source.sendSuccess(() -> Component.literal(result.id()
                + " | " + result.path() + " | " + (result.success() ? "OK" : "FAIL") + " | " + result.message()), false);
        if (reminderOutstanding) source.sendSuccess(() -> Component.literal(
                Messages.restart(settings, reminderAutoModpackOutstanding)), false);
        return Command.SINGLE_SUCCESS;
    }

    private int diagnoseCurseForge(CommandSourceStack source) {
        if (!begin(source, "DIAGNOSE_CURSEFORGE")) return 0;
        currentTask = worker.submit(() -> {
            try {
                Settings settings = Settings.load(FMLPaths.CONFIGDIR.get()).settings();
                String result = UpdateChecker.diagnoseCurseForge(settings);
                operation = "DIAGNOSE_COMPLETED";
                send(source, "[ModUpdates] CurseForge diagnose: " + result);
            } catch (Exception e) {
                operation = "DIAGNOSE_FAILED";
                fail(source, "[ModUpdates] CurseForge diagnose: " + e.getClass().getSimpleName());
            } finally {
                running.set(false);
                currentTask = null;
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int planList(CommandSourceStack source, int page) {
        Settings settings = settings();
        try {
            List<MarkdownPlanRepository.Summary> plans = new MarkdownPlanRepository(gameDir()).list();
            int pages = Math.max(1, (plans.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > pages) {
                source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                        "Page is outside range 1..", "Страница вне диапазона 1..") + pages));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("[ModUpdates] Plans page " + page + "/" + pages), false);
            int from = (page - 1) * PAGE_SIZE;
            for (MarkdownPlanRepository.Summary plan : plans.subList(from, Math.min(plans.size(), from + PAGE_SIZE))) {
                source.sendSuccess(() -> Component.literal(plan.planId() + " | selected=" + plan.selected()
                        + "/" + plan.total() + " | " + (plan.valid() ? "VALID" : "INVALID")), false);
            }
            return Command.SINGLE_SUCCESS;
        } catch (IOException e) {
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "Cannot list plans: ", "Невозможно показать планы: ") + e.getClass().getSimpleName()));
            return 0;
        }
    }

    private int planPreview(CommandSourceStack source, String requestedId) {
        String planId;
        try { planId = resolvePlanId(requestedId); }
        catch (IOException e) { return planFailure(source, e.getMessage()); }
        if (!begin(source, "PLAN_PREVIEW " + planId)) return 0;
        planCancelRequested.set(false);
        currentTask = worker.submit(() -> {
            try {
                PlanExecutor.Preview preview = new PlanExecutor(gameDir(), settings(),
                        new MarkdownPlanRepository(gameDir())).preview(planId, planCancelRequested::get);
                planActions = preview.actions();
                operation = preview.valid() ? "PLAN_PREVIEW_COMPLETED" : "PLAN_PREVIEW_INVALID";
                send(source, "[ModUpdates] plan=" + planId + "; selected=" + preview.selected()
                        + "; skipped=" + preview.skipped() + "; valid=" + preview.valid());
                for (PlanExecutor.Action action : preview.actions()) {
                    send(source, action.choiceId() + " | " + action.path() + " | " + action.provider().name().toLowerCase(Locale.ROOT)
                            + " | target=" + action.destinationPath() + " | " + action.status() + " | " + action.message());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                operation = "PLAN_PREVIEW_FAILED";
                fail(source, "[ModUpdates] " + Messages.text(settings(), "Plan preview failed: ", "Ошибка preview плана: ")
                        + safePlanError(e));
            } finally { running.set(false); currentTask = null; }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int planApply(CommandSourceStack source, String requestedId) {
        String planId;
        try { planId = resolvePlanId(requestedId); }
        catch (IOException e) { return planFailure(source, e.getMessage()); }
        if (!begin(source, "PLAN_APPLY " + planId)) return 0;
        planCancelRequested.set(false);
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(settings(),
                "Plan apply started; only checked and validated entries can change.",
                "Применение плана начато; изменяться могут только отмеченные и проверенные записи.")), false);
        currentTask = worker.submit(() -> {
            try {
                Settings settings = settings();
                PlanExecutor.Execution execution = new PlanExecutor(gameDir(), settings,
                        new MarkdownPlanRepository(gameDir())).apply(planId, planCancelRequested::get);
                PlanResultWriter.Written written = PlanResultWriter.write(gameDir(), execution, settings);
                planActions = execution.actions();
                lastPlanResult = written.markdown();
                reminderOutstanding |= execution.restartRequired();
                reminderAutoModpackOutstanding |= execution.autoModpackChanged();
                boolean failed = execution.actions().stream().anyMatch(action -> switch (action.status()) {
                    case STALE, INVALID, UNAVAILABLE, CONFLICT, STATE_ERROR, FAILED -> true;
                    default -> false;
                });
                operation = planCancelRequested.get() ? "PLAN_CANCELLED"
                        : failed ? "PLAN_PARTIAL_OR_FAILED" : "PLAN_COMPLETED";
                long installed = execution.actions().stream().filter(action -> action.status() == PlanExecutor.Status.INSTALLED).count();
                send(source, "[ModUpdates] plan=" + planId + "; installed=" + installed + "; result="
                        + written.markdown().toAbsolutePath().normalize());
                if (execution.restartRequired()) reminder(source, settings, execution.autoModpackChanged());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                operation = "PLAN_APPLY_FAILED";
                fail(source, "[ModUpdates] " + Messages.text(settings(), "Plan apply failed: ", "Ошибка применения плана: ")
                        + safePlanError(e));
            } finally { running.set(false); currentTask = null; }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int planStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[ModUpdates] operation=" + operation + "; active=" + running.get()
                + "; cancelRequested=" + planCancelRequested.get() + "; result="
                + (lastPlanResult == null ? "none" : lastPlanResult.toAbsolutePath().normalize())), false);
        for (PlanExecutor.Action action : planActions) {
            source.sendSuccess(() -> Component.literal(action.choiceId() + " | " + action.path() + " | "
                    + action.status() + " | " + action.message()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int planCancel(CommandSourceStack source) {
        Settings settings = settings();
        if (!running.get() || !operation.startsWith("PLAN_")) {
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "No plan operation is active.", "Операция плана не выполняется.")));
            return 0;
        }
        planCancelRequested.set(true);
        source.sendSuccess(() -> Component.literal("[ModUpdates] " + Messages.text(settings,
                "Cancellation requested; it will stop before the next file operation.",
                "Отмена запрошена; выполнение остановится перед следующей файловой операцией.")), false);
        return Command.SINGLE_SUCCESS;
    }

    private String resolvePlanId(String requested) throws IOException {
        if (requested != null) return new MarkdownPlanRepository(gameDir()).parse(requested).manifest().planId();
        return new MarkdownPlanRepository(gameDir()).list().stream().filter(MarkdownPlanRepository.Summary::valid)
                .map(MarkdownPlanRepository.Summary::planId).findFirst()
                .orElseThrow(() -> new IOException("no valid plan; run /modupdates check"));
    }

    private int planFailure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[ModUpdates] " + message));
        return 0;
    }

    private static int configPath(CommandSourceStack source) {
        Path path = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve(Settings.FILE_NAME);
        try {
            Settings.LoadResult loaded = Settings.load(FMLPaths.CONFIGDIR.get());
            Settings settings = loaded.settings();
            source.sendSuccess(() -> Component.literal("[ModUpdates] Config: " + path + "; loaded=true; created="
                    + loaded.created() + "; CurseForgeKey=" + (settings.curseforgeEnabled() ? "configured" : "not configured")
                    + (loaded.environmentKey() ? " (environment)" : "") + "; language=" + settings.language().configValue()
                    + (loaded.languageFallback() ? " (fallback from unsupported value)" : "")
                    + "; enableAutoModpack=" + settings.enableAutoModpack()), false);
            return Command.SINGLE_SUCCESS;
        } catch (IOException e) {
            source.sendFailure(Component.literal("[ModUpdates] Config: " + path + "; loaded=false; " + e.getClass().getSimpleName()));
            return 0;
        }
    }

    private static int help(CommandSourceStack source) {
        Settings settings = settings();
        for (String line : List.of(
                "/modupdates check | status | list [page] | configpath | help",
                "/modupdates update list [page] | update <id> <modrinth|curseforge> | update status",
                "/modupdates update all dryrun | update all confirm <token> | rollback <id>",
                "/modupdates plan list [page] | plan preview [planId] | plan apply [planId]",
                "/modupdates plan status | plan cancel",
                Messages.text(settings,
                        "/modupdates diagnose curseforge — all commands require OP level 2+; console is supported",
                        "/modupdates diagnose curseforge — все команды требуют OP level 2+; console поддерживается"))) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private boolean begin(CommandSourceStack source, String name) {
        Settings settings = settings();
        if (worker.isShutdown()) {
            source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings,
                    "The server is stopping; operation not started.", "Сервер останавливается; операция не запущена.")));
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            busy(source);
            return false;
        }
        operation = name;
        return true;
    }

    private static int busy(CommandSourceStack source) {
        source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings(),
                "Another operation is already running.", "Другая операция уже выполняется.")));
        return 0;
    }
    private static int noPlan(CommandSourceStack source) {
        source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings(),
                "No current plan; run /modupdates check.", "Нет текущего плана; выполните /modupdates check.")));
        return 0;
    }
    private static int unknownId(CommandSourceStack source) {
        source.sendFailure(Component.literal("[ModUpdates] " + Messages.text(settings(),
                "Unknown or stale ID; run /modupdates check.", "Неизвестный или устаревший ID; выполните /modupdates check.")));
        return 0;
    }
    private String[] planIds() {
        UpdatePlan plan = currentPlan;
        return plan == null ? new String[0] : plan.items().stream().map(UpdatePlan.Item::id).toArray(String[]::new);
    }
    private static Report.Provider parseProvider(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "modrinth" -> Report.Provider.MODRINTH;
            case "curseforge" -> Report.Provider.CURSEFORGE;
            default -> null;
        };
    }
    private static Report.Provider selectBulkProvider(UpdatePlan.Item item) {
        Report.Download modrinth = item.providers().get(Report.Provider.MODRINTH);
        Report.Download curseforge = item.providers().get(Report.Provider.CURSEFORGE);
        if (modrinth == null || !modrinth.actionable()) return Report.Provider.CURSEFORGE;
        if (curseforge == null || !curseforge.actionable()) return Report.Provider.MODRINTH;
        try {
            return Instant.parse(curseforge.releasedAt()).isAfter(Instant.parse(modrinth.releasedAt()))
                    ? Report.Provider.CURSEFORGE : Report.Provider.MODRINTH;
        } catch (DateTimeParseException ignored) {
            return Report.Provider.MODRINTH;
        }
    }
    private static void send(CommandSourceStack source, String message) {
        source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(message), false));
    }
    private static void fail(CommandSourceStack source, String message) {
        source.getServer().execute(() -> source.sendFailure(Component.literal(message)));
    }
    private static void reminder(CommandSourceStack source, Settings settings, boolean autoModpackChanged) {
        String reminder = Messages.restart(settings, autoModpackChanged);
        LOGGER.warn(reminder);
        send(source, reminder);
    }
    private String[] markdownPlanIds() {
        try { return new MarkdownPlanRepository(gameDir()).list().stream()
                .map(MarkdownPlanRepository.Summary::planId).toArray(String[]::new); }
        catch (IOException e) { return new String[0]; }
    }
    private static boolean isAutoModpackPath(String path) {
        return path != null && path.replace('\\', '/').startsWith("automodpack/");
    }
    private static String safePlanError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("(?i)https://\\S+", "<redacted-url>")
                .replaceAll("(?i)(api[_-]?key|token|signature)=[^\\s&]+", "$1=<redacted>");
    }
    private static Path gameDir() { return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize(); }
    private record Selection(UpdatePlan.Item item, Report.Provider provider) { }

    private static Settings initializeSettings() {
        Path path = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve(Settings.FILE_NAME);
        try {
            Settings.LoadResult loaded = Settings.load(FMLPaths.CONFIGDIR.get());
            LOGGER.info("ModUpdates configuration {} and loaded successfully: {} (CurseForge key source: {})",
                    loaded.created() ? "created" : "exists", loaded.path(),
                    loaded.environmentKey() ? "environment" : loaded.settings().curseforgeEnabled() ? "property" : "none");
            if (loaded.languageFallback()) LOGGER.warn("Unsupported modupdates language value; falling back to en_us");
            return loaded.settings();
        } catch (IOException e) {
            LOGGER.error("ModUpdates cannot create or load configuration at {}: {}", path, e.getClass().getSimpleName());
            return new Settings("", false, false, false);
        }
    }

    private static Settings settings() {
        try { return Settings.load(FMLPaths.CONFIGDIR.get()).settings(); }
        catch (IOException e) { return new Settings("", false, false, false); }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        worker.shutdownNow();
        running.set(false);
    }
}
