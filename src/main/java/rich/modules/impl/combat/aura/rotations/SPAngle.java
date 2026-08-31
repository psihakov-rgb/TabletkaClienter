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
 * SPAngle — bypass для античита SpookyTime (1.16.5 Anarchy).
 *
 * Логика античита:
 * - Следит за дистанцией до цели: близко → скорость↓, средняя → скорость↑, далеко → скорость↓
 * - Разница угол/цель: малая разница → замедление, большая → ускорение
 * - Питч вращается медленнее яу
 * - Редкие случайные подёргивания (jitter) + микротряска через синус
 * - Иногда prицел перекручивается (overshoot)
 * - GCD fix обязателен
 * - Если угол почти не изменился — оставляем старый
 */
public class SPAngle extends RotateConstructor {

    private final SecureRandom random = new SecureRandom();

    // Последние применённые углы (аналог lastYaw/lastPitch в описании античита)
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;

    public SPAngle() {
        super("SpookyTime");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3d vec3d, Entity entity) {
        StrikeManager attackHandler = Initialization.getInstance().getManager().getAttackPerpetrator().getAttackHandler();
        Aura aura = Aura.getInstance();
        boolean canAttack = entity != null && attackHandler.canAttack(aura.getConfig(), 0);

        // Если можем ударить — целимся в хитбокс точнее
        if (entity != null && canAttack) {
            Vec3d aimPoint = Vector.hitbox(entity, 1, entity.isOnGround() ? 1F : 1.256F, 1, 2);
            targetAngle = MathAngle.calculateAngle(aimPoint);
        }

        // Инициализируем lastYaw/lastPitch первый раз
        if (Float.isNaN(lastYaw)) {
            lastYaw = currentAngle.getYaw();
            lastPitch = currentAngle.getPitch();
        }

        // Целевые углы
        float targetYaw = targetAngle.getYaw();
        float targetPitch = MathHelper.clamp(targetAngle.getPitch(), -90.0f, 90.0f);

        // Случайные значения для текущего тика (имитация neuroRand)
        float neuroRand1 = random.nextFloat();
        float neuroRand2 = random.nextFloat();
        float neuroRand3 = random.nextFloat();
        float neuroRand4 = random.nextFloat();

        // Расстояние до цели (через eyes -> точку прицеливания)
        Vec3d eyes = (entity != null)
                ? entity.getEyePos().subtract(
                        MathHelper.clamp(0, 0, 0),
                        MathHelper.clamp(0, 0, 0),
                        MathHelper.clamp(0, 0, 0))
                : vec3d;
        // Более надёжный способ: берём дистанцию до entity
        float distToTarget = (entity != null)
                ? (float) mc.player.getEyePos().distanceTo(entity.getEyePos())
                : 3.0f;

        // TPS множитель — как в оригинале
        float tpsMultiplier = getTpsFactor();

        // Дистанционный фактор
        float distanceFactor;
        if (distToTarget < 2.0f) {
            distanceFactor = 0.8f + neuroRand1 * 0.4f;      // 0.8–1.2
        } else if (distToTarget < 4.0f) {
            distanceFactor = 1.2f + neuroRand2 * 0.6f;      // 1.2–1.8
        } else {
            distanceFactor = 0.9f + neuroRand3 * 0.5f;      // 0.9–1.4
        }

        // Базовая скорость поворота (как в оригинале)
        float baseSpeed = MathHelper.clamp(distToTarget * 0.25f, 0.8f, 3.0f)
                * distanceFactor * tpsMultiplier;

        // Разница между текущим углом и целью
        float yawDiff = MathHelper.wrapDegrees(targetYaw - lastYaw);
        float pitchDiff = targetPitch - lastPitch;

        // Лимит для AimModulo360 — 280° вместо 360° (Grim детектит 360°)
        if (Math.abs(yawDiff) > 280) {
            yawDiff = MathHelper.clamp(yawDiff, -280, 280);
        }

        // Smoothing factor в зависимости от дистанции и разницы углов
        float smoothFactorBase;
        if (distToTarget < 3.0f && Math.abs(yawDiff) < 10.0f) {
            // Близко + почти наведено → медленная ротация
            smoothFactorBase = 0.08f + neuroRand2 * 0.06f;   // 0.08–0.14
        } else if (distToTarget > 5.0f || Math.abs(yawDiff) > 30.0f) {
            // Далеко или большая разница → быстрая ротация
            smoothFactorBase = 0.18f + neuroRand3 * 0.12f;   // 0.18–0.30
        } else {
            smoothFactorBase = 0.12f + neuroRand1 * 0.08f;   // 0.12–0.20
        }

        // TPS адаптация
        float tpsAdapt = tpsMultiplier > 1.2f ? 0.9f : (tpsMultiplier < 0.8f ? 1.2f : 1.0f);
        smoothFactorBase *= tpsAdapt;

        // Вычисляем движение
        float smoothYaw = yawDiff * smoothFactorBase * (baseSpeed * 0.7f);
        // Питч вращается медленнее — чтобы не детектил Grim при быстрых поворотах
        float smoothPitch = pitchDiff * smoothFactorBase * (baseSpeed * 0.5f);

        // Редкое случайное подёргивание (jitter) с вероятностью 2%
        if (neuroRand4 < 0.02f) {
            smoothYaw += (random.nextFloat() - 0.5f) * 1.2f;
            smoothPitch += (random.nextFloat() - 0.5f) * 0.8f;
        }

        // Микротряска через синус (имитация дыхания/руки)
        float breathX = (float) Math.sin(System.currentTimeMillis() / 300.0) * 0.03f;
        float breathY = (float) Math.cos(System.currentTimeMillis() / 500.0) * 0.02f;
        smoothYaw += breathX;
        smoothPitch += breathY;

        // Overshoot: иногда перекручиваем прицел с 5% вероятностью при заметной разнице
        if (random.nextFloat() < 0.05f && Math.abs(yawDiff) > 5.0f) {
            float overshootFactor = 1.1f + random.nextFloat() * 0.3f;
            smoothYaw *= overshootFactor;
        }

        // Применяем изменения
        float newYaw = lastYaw + smoothYaw;
        float newPitch = MathHelper.clamp(lastPitch + smoothPitch, -90.0f, 90.0f);

        // GCD fix — синхронизируем с чувствительностью мыши
        double gcd = MathUtils.computeGcd();
        newYaw = newYaw - (newYaw - lastYaw) % (float) gcd;
        newPitch = newPitch - (newPitch - lastPitch) % (float) gcd;

        // Если угол почти не изменился — не двигаем (избегаем "тремора пикселей")
        if (Math.abs(newYaw - lastYaw) < 0.01f && Math.abs(newPitch - lastPitch) < 0.01f) {
            newYaw = lastYaw;
            newPitch = lastPitch;
        }

        lastYaw = newYaw;
        lastPitch = newPitch;

        return new Angle(newYaw, MathHelper.clamp(newPitch, -90.0f, 90.0f));
    }

    /**
     * Возвращает TPS-множитель: при нормальных 20 TPS = 1.0,
     * при лагах < 15 TPS = до 1.3, при ускорении > 20 TPS = до 0.9.
     */
    private float getTpsFactor() {
        float tps = rich.util.network.Network.TPS;
        if (tps <= 0) return 1.0f;
        float factor = 20.0f / tps;
        return MathHelper.clamp(factor, 0.7f, 1.4f);
    }

    @Override
    public Vec3d randomValue() {
        return Vec3d.ZERO;
    }
}
