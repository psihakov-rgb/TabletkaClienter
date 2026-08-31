package rich.modules.impl.combat;

import antidaunleak.api.annotation.Native;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import rich.events.api.EventHandler;
import rich.events.impl.PacketEvent;
import rich.events.impl.TickEvent;
import rich.modules.impl.combat.aura.AngleConnection;
import rich.modules.module.ModuleStructure;
import rich.modules.module.category.ModuleCategory;
import rich.modules.module.setting.implement.BooleanSetting;
import rich.modules.module.setting.implement.SelectSetting;
import rich.modules.module.setting.implement.SliderSettings;
import rich.util.Instance;
import rich.util.timer.StopWatch;

import java.security.SecureRandom;

/**
 * SpookyBypass — обход античита SpookyTime (SpChecker + Matrix) на 1.16.5.
 *
 * ── Что детектит SpChecker + Matrix ─────────────────────────────────────────
 *
 * 1. SPRINT-АТАКА БЕЗ СБРОСА (Matrix KillAura A/B)
 *    Matrix отслеживает: когда игрок бьёт в спринте, перед
 *    PlayerInteractEntity должен быть ClientCommand(STOP_SPRINTING).
 *    Если этого пакета нет — VL растёт. Накопив нужный VL — бан/кик.
 *
 * 2. АНОМАЛЬНЫЙ ПОРЯДОК ПАКЕТОВ (SpChecker)
 *    SpChecker смотрит на последовательность: [PlayerMove(yaw/pitch)] →
 *    [PlayerInteractEntity]. Если между ними нет PlayerMove с обновлёнными
 *    углами — "silent rotation" детект. Клиент повернулся, но сервер не
 *    получил движение — значит поворот был программным.
 *
 * 3. СЛИШКОМ РОВНЫЙ CPS (SpChecker ритм-детект)
 *    Человек бьёт с непостоянными интервалами. Если интервал между
 *    PlayerInteractEntity пакетами постоянный (±5мс) — флаг авто-кликера.
 *
 * 4. RUBBER-BAND = ФЛАГ (косвенный признак)
 *    Если сервер телепортирует игрока назад (PlayerPositionLook) — он уже
 *    выдал VL за что-то. Нужно снизить активность.
 *
 * 5. СПРИНТ ЛАГАЕТ БЕЗ СБРОСА (клиентская проблема)
 *    Стандартный setSprinting(false) блокирует движение на 1-2 тика.
 *    Пакетный режим отправляет только пакет серверу, не трогая
 *    клиентский флаг — спринт на клиенте не прерывается, нет лага.
 *
 * ── Что делает этот модуль ───────────────────────────────────────────────────
 *
 *  canAttackNow()   — проверяет задержки и антифлаг, вызывается ДО атаки
 *  prepareAttack()  — выполняет спринт-ресет + порядок пакетов, вызывается
 *                     непосредственно перед executeAttack()
 */
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SpookyBypass extends ModuleStructure {

    public static SpookyBypass getInstance() {
        return Instance.get(SpookyBypass.class);
    }

    // ── Настройки ──────────────────────────────────────────────────────────────

    SelectSetting sprintResetMode = new SelectSetting("Сброс спринта",
            "Пакетный = без лага спринта на клиенте; Легитный = надёжнее для старых версий Matrix")
            .value("Пакетный", "Легитный", "Отключён")
            .selected("Пакетный");

    BooleanSetting packetOrderFix = new BooleanSetting("Фикс порядка пакетов",
            "Отправляет LookAndOnGround перед атакой. SpChecker не видит удара без предшествующего угла")
            .setValue(true);

    BooleanSetting randomizeDelay = new BooleanSetting("Рандомизация задержки",
            "Случайный интервал между атаками — обходит ритм-детект CPS SpChecker")
            .setValue(true);

    SliderSettings delayMax = new SliderSettings("Макс. задержка (мс)",
            "Верхняя граница случайной задержки. 25-40 мс = незаметно, >60 мс = безопаснее")
            .range(0f, 100f)
            .setValue(35f)
            .visible(() -> randomizeDelay.isValue());

    BooleanSetting antiFlagMode = new BooleanSetting("Антифлаг",
            "При rubber-band от сервера временно снижает активность атак")
            .setValue(true);

    BooleanSetting sprintRestoreDelay = new BooleanSetting("Задержка восстановления спринта",
            "Восстанавливает спринт через 1-2 тика — имитирует человека, убирает флаги Matrix")
            .setValue(true);

    // ── Состояние ──────────────────────────────────────────────────────────────

    @NonFinal boolean sprintWasActive    = false;
    @NonFinal boolean pendingRestore     = false;
    @NonFinal int     restoreTick        = 0;
    @NonFinal int     rubberbandCount    = 0;
    @NonFinal long    pendingDelayUntil  = 0L;

    StopWatch rubberbandWatch = new StopWatch();
    SecureRandom random = new SecureRandom();

    // ── Конструктор ────────────────────────────────────────────────────────────

    public SpookyBypass() {
        super("SpookyBypass", "Bypass SpChecker + Matrix (SpookyTime 1.16.5)", ModuleCategory.COMBAT);
        settings(sprintResetMode, packetOrderFix, randomizeDelay, delayMax,
                antiFlagMode, sprintRestoreDelay);
    }

    // ── Публичный API (вызывается из StrikeManager) ────────────────────────────

    /**
     * Проверяет, можно ли атаковать прямо сейчас.
     * Вызывается ДО preAttackEntity, чтобы не снимать щит зря.
     * @return true — атака разрешена, false — пропустить этот тик
     */
    @Native(type = Native.Type.VMProtectBeginUltra)
    public boolean canAttackNow() {
        if (mc.player == null) return true;
        long now = System.currentTimeMillis();

        // Ждём рандомизированную задержку
        if (now < pendingDelayUntil) return false;

        // Антифлаг: rubber-band был недавно (< 4 сек) и счётчик ≥ 3 — пауза
        if (antiFlagMode.isValue()
                && rubberbandCount >= 3
                && !rubberbandWatch.finished(4000)) {
            return false;
        }

        return true;
    }

    /**
     * Выполняет подготовку непосредственно перед executeAttack:
     * 1. Сбрасывает спринт (пакетно или легитно)
     * 2. Отправляет PlayerMove с актуальным углом
     * 3. Планирует восстановление спринта через N тиков
     * 4. Устанавливает следующую рандомизированную задержку
     */
    @Native(type = Native.Type.VMProtectBeginUltra)
    public void prepareAttack() {
        if (mc.player == null) return;

        doSprintReset();

        if (packetOrderFix.isValue()) {
            sendPreAttackMove();
        }

        if (randomizeDelay.isValue()) {
            int maxMs = (int) delayMax.getValue();
            if (maxMs > 0) {
                // Минимальная задержка 5 мс — исключаем "0мс между ударами"
                pendingDelayUntil = System.currentTimeMillis() + 5 + random.nextInt(maxMs);
            }
        }
    }

    // ── Тик ───────────────────────────────────────────────────────────────────

    @EventHandler
    @Native(type = Native.Type.VMProtectBeginUltra)
    public void onTick(TickEvent e) {
        if (mc.player == null) return;

        // Восстанавливаем спринт с задержкой
        if (pendingRestore) {
            if (restoreTick <= 0) {
                doSprintRestore();
            } else {
                restoreTick--;
            }
        }

        // Сбрасываем счётчик rubber-band если прошло достаточно времени
        if (rubberbandCount > 0 && rubberbandWatch.finished(8000)) {
            rubberbandCount = 0;
        }
    }

    // ── Пакеты ────────────────────────────────────────────────────────────────

    @EventHandler
    @Native(type = Native.Type.VMProtectBeginUltra)
    public void onPacket(PacketEvent e) {
        if (e.getType() != PacketEvent.Type.RECEIVE) return;
        if (!(e.getPacket() instanceof PlayerPositionLookS2CPacket)) return;

        // Сервер телепортировал нас — это rubber-band, значит что-то спалилось
        rubberbandCount++;
        rubberbandWatch.reset();

        if (antiFlagMode.isValue() && rubberbandCount >= 2) {
            // Пауза 200-400 мс после rubber-band
            long pause = 200L + random.nextInt(200);
            pendingDelayUntil = System.currentTimeMillis() + pause;
        }
    }

    // ── Вспомогательные ───────────────────────────────────────────────────────

    /**
     * Сбрасывает спринт перед атакой.
     *
     * Пакетный режим: отправляет STOP_SPRINTING пакет серверу, НЕ трогая
     * клиентский флаг — игрок на клиенте продолжает спринтовать без рывка,
     * сервер получает легитный пакет. Это ключ к отсутствию лага при спринте.
     *
     * Легитный режим: setSprinting(false) — прерывает движение на 1-2 тика,
     * но более надёжно работает с ранними версиями Matrix.
     */
    @Native(type = Native.Type.VMProtectBeginMutation)
    private void doSprintReset() {
        if (mc.player == null) return;
        if (sprintResetMode.isSelected("Отключён")) return;

        sprintWasActive = mc.player.isSprinting();
        if (!sprintWasActive) return;  // не в спринте — ничего не делаем

        if (sprintResetMode.isSelected("Пакетный")) {
            // Только пакет серверу — клиент не знает, лага нет
            mc.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        } else {
            // Легитный: меняем флаг клиента (может быть небольшой рывок)
            mc.player.setSprinting(false);
        }

        pendingRestore = true;
        restoreTick = sprintRestoreDelay.isValue() ? (1 + random.nextInt(2)) : 0;
    }

    /**
     * Восстанавливает спринт после атаки.
     * Пакетный режим отправляет START_SPRINTING, клиент не прерывался.
     */
    @Native(type = Native.Type.VMProtectBeginMutation)
    private void doSprintRestore() {
        pendingRestore = false;
        if (mc.player == null || !sprintWasActive) {
            sprintWasActive = false;
            return;
        }

        if (sprintResetMode.isSelected("Пакетный")) {
            mc.getNetworkHandler().sendPacket(
                    new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        } else if (sprintResetMode.isSelected("Легитный")) {
            if (mc.player.input.hasForwardMovement() && !mc.player.isUsingItem()) {
                mc.player.setSprinting(true);
            }
        }

        sprintWasActive = false;
    }

    /**
     * Отправляет LookAndOnGround с текущими углами перед атакой.
     *
     * SpChecker строит цепочку: [...PlayerMove(yaw)] → [InteractEntity].
     * Если цепочка нарушена (InteractEntity без предшествующего Move) —
     * сервер видит удар "из ниоткуда". Этот пакет легитимизирует удар.
     *
     * Важно: отправляем РЕАЛЬНЫЙ угол (через AngleConnection) а не
     * mc.player.getYaw() который может быть ещё не обновлён на этом тике.
     */
    @Native(type = Native.Type.VMProtectBeginMutation)
    private void sendPreAttackMove() {
        if (mc.player == null) return;

        float yaw   = AngleConnection.INSTANCE.getRotation() != null
                ? AngleConnection.INSTANCE.getRotation().getYaw()
                : mc.player.getYaw();
        float pitch = AngleConnection.INSTANCE.getRotation() != null
                ? AngleConnection.INSTANCE.getRotation().getPitch()
                : mc.player.getPitch();

        mc.getNetworkHandler().sendPacket(
                new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), false));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    @Native(type = Native.Type.VMProtectBeginMutation)
    public void activate() {
        super.activate();
        sprintWasActive   = false;
        pendingRestore    = false;
        restoreTick       = 0;
        rubberbandCount   = 0;
        pendingDelayUntil = 0L;
    }

    @Override
    @Native(type = Native.Type.VMProtectBeginMutation)
    public void deactivate() {
        super.deactivate();
        // Гарантируем восстановление спринта при выключении
        if (pendingRestore && mc.player != null && sprintWasActive) {
            if (sprintResetMode.isSelected("Пакетный")) {
                mc.getNetworkHandler().sendPacket(
                        new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
            } else if (sprintResetMode.isSelected("Легитный")) {
                mc.player.setSprinting(true);
            }
        }
        pendingRestore  = false;
        sprintWasActive = false;
    }
}
