package segway.controller;

import segway.Constants;

/**
 * The controller that calculates the motor control signal from the
 * current sensors values.
 *
 * @author Max Morozov
 */
public class BalanceController extends MotorbikeWheels {
    /**
     * If robot power is saturated (over +/- 100) for over this time limit then
     * robot must have fallen.  In milliseconds.
     */
    private static final double TIME_FALL_LIMIT = 1000;

    static final float CMD_MAX = 100.0f;
    static final float POWER_MAX = 100.0f;

    static final float EXEC_PERIOD = Constants.CONTROLLER_TIME / 1000f;
    static final float EXEC_FREQUENCY = 1 / EXEC_PERIOD;

    static final float A_D = 0.9f;//0.8F;       /* low pass filter gain for motors average count */
    static final float A_R = 0.996F;     /* low pass filter gain for motors target count */

    static final float K_PHIDOT = 25.0F;         /* turn target speed gain */
    static final float K_SYNC = 0.35F;           /* wheel synchronization gain */

    static final float BATTERY_GAIN = 0.001089F; /* battery voltage gain for motor PWM outputs */
    static final float BATTERY_OFFSET = 0.625F;  /* battery voltage offset for motor PWM outputs */

    //state variables on the previous step
    private float prior_err_theta = 0;
    private float prior_theta_lpf = 0;
    private float prior_theta_ref = 0;
    private float prior_thetadot_cmd_lpf = 0; //wheels angular velocity, filtered by Low Path Filter to suppress rapid input change.

    //Wheels synchronization part
    private boolean prior_flag_turn = false;
    private int theta_offset = 0;

    //these variables are used to detect falling
    private boolean firstStep = true;
    private long lastGoodRegulationTime;


    /**
     * Performs the regulation cycle. Execution time is 0.583 ms
     *
     * @param cmd_forward     speed of the forward movement. -100(backward max.) to 100(forward max.)
     * @param cmd_turn        speed of the turning. -100(turn left max.) to 100(turn right max.)
     * @param psidot          the body angular velocity
     * @param psi             body pitch
     * @param left_motor_pos  left motor rotor position in degrees
     * @param right_motor_pos right motor rotor position in degrees
     * @param battery_voltage the battery voltage in milli-volts (mV)
     * @return encoded power for left and right motors. Low byte - left motor, high byte - right motor
     */
    public short control(int cmd_forward, int cmd_turn, float psidot, float psi, int left_motor_pos, int right_motor_pos, float battery_voltage) {
        if (firstStep) {
            lastGoodRegulationTime = System.currentTimeMillis();
            firstStep = false;
        }

        //Smooth velocity command using Low Pass Filter to suppress rapid input change.
        float thetadot_cmd_lpf = (((cmd_forward / CMD_MAX) * K_THETADOT) * (1 - A_R)) + (A_R * prior_thetadot_cmd_lpf);

        //Calculate the wheel position
        float theta = (left_motor_pos + right_motor_pos) / 2.0f + psi;

        //Smooth measured velocity value using Low Pass Filter to reduce the noise because it makes extra control input.
        float theta_lpf = (1 - A_D) * theta + A_D * prior_theta_lpf;

        //Calculate wheels' angular velocity by differentiating the wheels' position
        float theta_dot = (theta_lpf - prior_theta_lpf) * EXEC_FREQUENCY;

        //calculating (x_ref - x)*KF where x_ref and x are vectors and KF is the Feedback Gain column
        //the reference values for psi and psidot are 0.
        float volume = (prior_theta_ref - theta) * K_F1
                - psi * K_F2
                + (thetadot_cmd_lpf - theta_dot) * K_F3 //calculating the motor speed using discrete derivative
                - psidot * K_F4;

        //adding integral of error
        volume += K_I * prior_err_theta;

        float power = (volume / (BATTERY_GAIN * battery_voltage - BATTERY_OFFSET)) * POWER_MAX;

        if (Math.abs(power) < POWER_MAX)
            lastGoodRegulationTime = System.currentTimeMillis();

        float pwm_turn = (cmd_turn / CMD_MAX) * K_PHIDOT;

        //Wheels synchronization
        float wheelSyncDelta = 0;
        boolean flag_turn = cmd_turn != 0;
        if (!flag_turn) {
            int theta_diff = left_motor_pos - right_motor_pos;
            if (prior_flag_turn) {
                theta_offset = theta_diff;
            }
            wheelSyncDelta = (theta_diff - theta_offset) * K_SYNC;
        }
        prior_flag_turn = flag_turn;

        //Limiting the motor power
        byte pwm_l = (byte) saturate(power + pwm_turn, -POWER_MAX, POWER_MAX);
        byte pwm_r = (byte) saturate(power - pwm_turn + wheelSyncDelta, -POWER_MAX, POWER_MAX);

        //Integrating the reference wheel rotation speed to get next  wheel position
        float temp_theta_ref = EXEC_PERIOD * thetadot_cmd_lpf + prior_theta_ref;
        //Integrating the regulation error
        prior_err_theta = (prior_theta_ref - theta) * EXEC_PERIOD + prior_err_theta;
        prior_theta_ref = temp_theta_ref;

        prior_thetadot_cmd_lpf = thetadot_cmd_lpf;
        prior_theta_lpf = theta_lpf;

        return (short) ((pwm_l & 0xFF) | ((pwm_r & 0xFF) << 8));
    }

    /**
     * Check if robot has fallen by detecting that motor power is being limited
     * for an extended amount of time.
     *
     * @return true if the motor power is not saturated
     */
    public boolean isOk() {
        return System.currentTimeMillis() - lastGoodRegulationTime < TIME_FALL_LIMIT;
    }

    private float saturate(float value, float min, float max) {
        if (value < min)
            return min;
        else if (value > max)
            return max;
        else
            return value;
    }
}
