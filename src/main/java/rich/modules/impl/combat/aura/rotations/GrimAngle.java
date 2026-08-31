package rich.modules.impl.combat.aura.rotations;

import rich.Initialization;
import rich.modules.impl.combat.Aura;
import rich.modules.impl.combat.aura.Angle;
import rich.modules.impl.combat.aura.AngleConnection;
import rich.modules.impl.combat.aura.MathAngle;
import rich.modules.impl.combat.aura.attack.StrikeManager;
import rich.modules.impl.combat.aura.impl.RotateConstructor;
import rich.modules.impl.combat.aura.target.RaycastAngle;
import rich.modules.impl.combat.aura.target.Vector;
import rich.util.math.MathUtils;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.security.SecureRandom;

/**
 * GrimAngle — bypass для античита Grim (1.16.5–1.21+).
 *
 * Что проверяет Grim:
 * 1. Углы поворота > 360° за тик (AimModulo360)
 * 2. Слишком высокая скорость поворота головы по вертикали
 * 3. Идеально "ровные" повороты без GCD-погрешности (sensitivity fingerprint)
 * 4. Паттерны поворота: постоянная скорость без ускорений/замедлений
 * 5. Нереалистичные повороты: мгновенный snap к цели
 *
 * Что делаем для обхода:
 * - Скорость поворота строго ограничена (MAX_YAW_PER_TICK / MAX_PITCH_PER_TICK)
 * - Применяем GCD fix от чувствительности мыши
 * - Добавляем плавное ускорение/замедление (easing) — не постоянная скорость
 * - Микроджиттер через sin/cos с разными частотами
 * - Питч вращается медленнее яу (≈60% от скорости яу)
 * - Никогда не превышаем 179° за тик по яу и 45° по питчу
 * - Когда прицел почти на цели — очень медленная доводка (µ-aiming)
 */
public class GrimAngle extends RotateConstructor {

    // Grim детектит повороты > ~180° за тик как телепортацию взгляда
    private static final float MAX_YAW_PER_TICK   = 50.0f;
    // Pitch значительно медленнее — Grim строже проверяет вертикаль
    private static final float MAX_PITCH_PER_TICK = 30.0f;

    private final SecureRandom random = new SecureRandom();

    // Накопленная "инерция" скорости для плавного easing
    private float velocityYaw   = 0f;
    private float velocityPitch = 0f;

    // Последние применённые углы
    private float lastYaw   = Float.NaN;
    private float lastPitch = Float.NaN;

    // Фаза для микроджиттера
    private long startTime = System.currentTimeMillis();

    public GrimAngle() {
        super("Grim");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3d vec3d, Entity entity) {
        StrikeManager attackHandler = Initialization.getInstance().getManager()
                .getAttackPerpetrator().getAttackHandler();
        Aura aura = Aura.getInstance();
        boolean canAttack = entity != null && attackHandler.canAttack(aura.getConfig(), 0);

        // При атаке — прицеливаемся в хитбокс
        if (entity != null && canAttack) {
            Vec3d aimPoint = Vector.hitbox(entity, 1, entity.isOnGround() ? 1.0F : 1.3F, 1, 2);
            targetAngle = MathAngle.calculateAngle(aimPoint);
        }

        // Инициализация первого тика
        if (Float.isNaN(lastYaw)) {
            lastYaw   = currentAngle.getYaw();
            lastPitch = currentAngle.getPitch();
        }

        float targetYaw   = targetAngle.getYaw();
        float targetPitch = MathHelper.clamp(targetAngle.getPitch(), -90.0f, 90.0f);

        // Разница от текущей позиции до цели
        float yawDiff   = MathHelper.wrapDegrees(targetYaw - lastYaw);
        float pitchDiff = targetPitch - lastPitch;

        // Дистанция до цели (для динамики скорости)
        float distToTarget = (entity != null)
                ? (float) mc.player.getEyePos().distanceTo(entity.getEyePos())
                : 3.0f;

        float totalDiff = (float) Math.hypot(Math.abs(yawDiff), Math.abs(pitchDiff));

        // --- Easing: плавное ускорение к цели (spring damper) ---
        // Чем дальше цель — тем агрессивнее ускорение, чем ближе — тем мягче
        float targetVelocityYaw;
        float targetVelocityPitch;

        if (totalDiff < 1.5f) {
            // µ-aiming: очень медленная доводка когда почти наведены
            targetVelocityYaw   = yawDiff   * 0.25f;
            targetVelocityPitch = pitchDiff * 0.15f;
        } else if (totalDiff < 8.0f) {
            // Средняя зона — плавно
            float speedScale = 0.35f + random.nextFloat() * 0.15f;
            targetVelocityYaw   = yawDiff   * speedScale;
            targetVelocityPitch = pitchDiff * speedScale * 0.6f;
        } else if (totalDiff < 25.0f) {
            // Быстро, но не слишком
            float speedScale = 0.55f + random.nextFloat() * 0.15f;
            targetVelocityYaw   = yawDiff   * speedScale;
            targetVelocityPitch = pitchDiff * speedScale * 0.6f;
        } else {
            // Большой угол → ускоряемся до максимума
            float speedScale = 0.70f + random.nextFloat() * 0.10f;
            targetVelocityYaw   = MathHelper.clamp(yawDiff   * speedScale, -MAX_YAW_PER_TICK,   MAX_YAW_PER_TICK);
            targetVelocityPitch = MathHelper.clamp(pitchDiff * speedScale * 0.6f, -MAX_PITCH_PER_TICK, MAX_PITCH_PER_TICK);
        }

        // Инерционное сглаживание — скорость не меняется мгновенно
        float inertiaFactor = 0.55f + random.nextFloat() * 0.10f;
        velocityYaw   += (targetVelocityYaw   - velocityYaw)   * inertiaFactor;
        velocityPitch += (targetVelocityPitch - velocityPitch) * inertiaFactor;

        // Жёсткий лимит за тик — Grim строго проверяет это
        velocityYaw   = MathHelper.clamp(velocityYaw,   -MAX_YAW_PER_TICK,   MAX_YAW_PER_TICK);
        velocityPitch = MathHelper.clamp(velocityPitch, -MAX_PITCH_PER_TICK, MAX_PITCH_PER_TICK);

        float smoothYaw   = velocityYaw;
        float smoothPitch = velocityPitch;

        // --- Микроджиттер (натуральная нестабильность руки) ---
        long elapsed = System.currentTimeMillis() - startTime;
        // Используем разные частоты чтобы паттерн не был периодическим
        float jitterYaw   = (float) (Math.sin(elapsed / 213.0) * 0.04f
                + Math.sin(elapsed / 97.0)  * 0.02f);
        float jitterPitch = (float) (Math.cos(elapsed / 347.0) * 0.025f
                + Math.cos(elapsed / 131.0) * 0.015f);

        smoothYaw   += jitterYaw;
        smoothPitch += jitterPitch;

        // Редкое случайное подёргивание (1.5% вероятность)
        if (random.nextFloat() < 0.015f) {
            smoothYaw   += (random.nextFloat() - 0.5f) * 0.8f;
            smoothPitch += (random.nextFloat() - 0.5f) * 0.4f;
        }

        // Результирующие углы
        float newYaw   = lastYaw   + smoothYaw;
        float newPitch = MathHelper.clamp(lastPitch + smoothPitch, -90.0f, 90.0f);

        // --- GCD fix ---
        // Без этого Grim определяет нереалистичную чувствительность
        double gcd = MathUtils.computeGcd();
        newYaw   = newYaw   - (newYaw   - lastYaw)   % (float) gcd;
        newPitch = newPitch - (newPitch - lastPitch) % (float) gcd;

        // Если изменение ничтожно — не трогаем (нет смысла слать пакет)
        if (Math.abs(newYaw - lastYaw) < 0.005f && Math.abs(newPitch - lastPitch) < 0.005f) {
            newYaw   = lastYaw;
            newPitch = lastPitch;
        }

        lastYaw   = newYaw;
        lastPitch = newPitch;

        return new Angle(newYaw, MathHelper.clamp(newPitch, -90.0f, 90.0f));
    }

    @Override
    public Vec3d randomValue() {
        return Vec3d.ZERO;
    }
}
