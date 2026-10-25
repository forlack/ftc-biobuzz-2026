package org.firstinspires.ftc.teamcode.lib;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.dashboard.Dash;

import java.util.Locale;

/**
 * Telemetry that goes to the Driver Station AND the Total Chaos Dashboard. Wrap it once at the
 * start of an OpMode and keep using {@code telemetry} as usual (the same idea as FTC
 * Dashboard's MultipleTelemetry):
 *
 * <pre>
 * telemetry = new DashboardTelemetry(telemetry);
 * telemetry.addData("Pollen RPM", pollenRpm);       // Driver Station line + dashboard (chartable)
 * telemetry.addData("Pollen", "%.0f RPM", pollenRpm); // formatted line; the dashboard still gets the number
 * Dash.put("Pollen target", target);                 // dashboard only, for charts
 * telemetry.update();                                // once per loop: also ends the dashboard frame
 * </pre>
 *
 * Numbers and booleans can be charted on the Live tab; everything else shows in its table.
 * {@code addLine} text stays on the Driver Station only.
 */
public class DashboardTelemetry implements Telemetry {
    private final Telemetry driverStation;

    public DashboardTelemetry(Telemetry driverStation) {
        // Wrapping twice would send every value twice.
        this.driverStation = driverStation instanceof DashboardTelemetry
                ? ((DashboardTelemetry) driverStation).driverStation : driverStation;
    }

    @Override public Item addData(String caption, Object value) {
        Dash.put(caption, value);
        return driverStation.addData(caption, value);
    }

    @Override public Item addData(String caption, String format, Object... args) {
        // One number formatted for drivers ("%.0f RPM") still reaches the charts as a number.
        Dash.put(caption, args.length == 1 && args[0] instanceof Number ? args[0] : String.format(Locale.US, format, args));
        return driverStation.addData(caption, format, args);
    }

    @Override public <T> Item addData(String caption, Func<T> valueProducer) {
        Dash.put(caption, valueProducer.value());
        return driverStation.addData(caption, valueProducer);
    }

    @Override public <T> Item addData(String caption, String format, Func<T> valueProducer) {
        Dash.put(caption, valueProducer.value());
        return driverStation.addData(caption, format, valueProducer);
    }

    /** Shows this loop's lines on the Driver Station and sends one dashboard frame. */
    @Override public boolean update() {
        Dash.frame();
        return driverStation.update();
    }

    // Everything else is the Driver Station's own telemetry.
    @Override public boolean removeItem(Item item) { return driverStation.removeItem(item); }
    @Override public void clear() { driverStation.clear(); }
    @Override public void clearAll() { driverStation.clearAll(); }
    @Override public Object addAction(Runnable action) { return driverStation.addAction(action); }
    @Override public boolean removeAction(Object token) { return driverStation.removeAction(token); }
    @Override public void speak(String text) { driverStation.speak(text); }
    @Override public void speak(String text, String languageCode, String countryCode) { driverStation.speak(text, languageCode, countryCode); }
    @Override public Line addLine() { return driverStation.addLine(); }
    @Override public Line addLine(String lineCaption) { return driverStation.addLine(lineCaption); }
    @Override public boolean removeLine(Line line) { return driverStation.removeLine(line); }
    @Override public boolean isAutoClear() { return driverStation.isAutoClear(); }
    @Override public void setAutoClear(boolean autoClear) { driverStation.setAutoClear(autoClear); }
    @Override public int getMsTransmissionInterval() { return driverStation.getMsTransmissionInterval(); }
    @Override public void setMsTransmissionInterval(int msTransmissionInterval) { driverStation.setMsTransmissionInterval(msTransmissionInterval); }
    @Override public String getItemSeparator() { return driverStation.getItemSeparator(); }
    @Override public void setItemSeparator(String itemSeparator) { driverStation.setItemSeparator(itemSeparator); }
    @Override public String getCaptionValueSeparator() { return driverStation.getCaptionValueSeparator(); }
    @Override public void setCaptionValueSeparator(String captionValueSeparator) { driverStation.setCaptionValueSeparator(captionValueSeparator); }
    @Override public void setDisplayFormat(DisplayFormat displayFormat) { driverStation.setDisplayFormat(displayFormat); }
    @Override public void setNumDecimalPlaces(int minDecimalPlaces, int maxDecimalPlaces) { driverStation.setNumDecimalPlaces(minDecimalPlaces, maxDecimalPlaces); }
    @Override public Log log() { return driverStation.log(); }
}
