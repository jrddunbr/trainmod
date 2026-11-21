package org.ja4.trainmod;

/** Server-side boundary for replacing the timed supplies with a boiler simulation. */
interface SteamTraction {
    record Input(double throttle) {}
    record Output(double power, int coalTicks, int waterTicks) {}

    Output evaluate(Input input, boolean coalAvailable, boolean waterAvailable);

    SteamTraction TIMED = (input, coal, water) -> input.throttle() != 0 && coal && water
        ? new Output(input.throttle(), 1, 1) : new Output(0, 0, 0);
}
