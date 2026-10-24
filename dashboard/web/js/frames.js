// Turns the robot's compact telemetry messages back into frames: {seq, t, n: {name: number},
// s: {name: text}, pose}. The robot sends each value's name once ("names"), then each loop as
// numbers in that order, with text only when it changes. See TelemetryStream.java.
export class FrameDecoder {
  constructor() {
    this.boot = null;
    this.names = [];
  }

  /** Returns {restarted, frames} for a telemetry message, or null for anything else. */
  handle(message) {
    if (message.type === 'hello') {
      const restarted = this.boot !== null && this.boot !== message.boot;
      this.boot = message.boot;
      this.names = [];   // the robot sends every name again on each connection
      return { restarted, frames: [] };
    }
    if (message.type === 'names') {
      message.names.forEach((name, i) => { this.names[message.from + i] = name; });
      return { restarted: false, frames: [] };
    }
    if (message.type === 'frames') {
      return { restarted: false, frames: message.frames.map((f) => this.decode(f)) };
    }
    return null;
  }

  decode([seq, t, values, text, pose]) {
    const n = {};
    values.forEach((value, i) => { if (value !== null) n[this.names[i]] = value; });
    const frame = { seq, t, n };
    if (text) {
      frame.s = {};
      for (const [i, value] of Object.entries(text)) frame.s[this.names[i]] = value;
    }
    if (pose) frame.pose = pose;
    return frame;
  }
}
