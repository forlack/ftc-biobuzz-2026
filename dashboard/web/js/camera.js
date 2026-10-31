// The Live field card's camera view: the robot's webcam picture, with the hive tags outlined
// (Vision.CAMERA_FEED_FPS a second), while the card is open and the Live tab is showing. The robot only takes
// pictures while someone asks for them, so a closed card costs nothing.

const SHOWN = 'biobuzz.cameraShown';

export class CameraView {
  constructor() {
    this.card = document.getElementById('camera-card');   // the Camera card, below the field
    this.image = document.getElementById('camera-image');
    this.note = document.getElementById('camera-note');
    this.box = document.getElementById('camera-toggle');   // the Camera checkbox
    this.url = null;
    this.busy = false;
    this.next = 0;
    this.shown = true;
    try {
      this.shown = localStorage.getItem(SHOWN) !== 'no';
    } catch {
      // private window: shown
    }
    this.box.addEventListener('change', () => {
      this.shown = this.box.checked;
      try {
        localStorage.setItem(SHOWN, this.shown ? 'yes' : 'no');
      } catch {
        // private window
      }
      this.show();
    });
    this.show();
    this.number = 0;
    setInterval(() => this.tick(), 20);
  }

  show() {
    this.card.classList.toggle('closed', !this.shown);
    this.box.checked = this.shown;
    window.dispatchEvent(new Event('resize'));   // the field's size changed
  }

  async tick() {
    const visible = document.getElementById('tab-live').classList.contains('active') && !document.hidden;
    if (!this.shown || !visible || this.busy || Date.now() < this.next) {
      return;
    }
    this.busy = true;
    try {
      // "The next picture after the one shown": the robot answers as soon as it is made.
      const response = await fetch(`/api/camera?after=${this.number || 0}`, { cache: 'no-store' });
      if (response.ok) {
        this.number = Number(response.headers.get('X-Picture')) || 0;
        const url = URL.createObjectURL(await response.blob());
        if (this.url) {
          URL.revokeObjectURL(this.url);
        }
        this.url = url;
        this.image.src = url;
        this.image.hidden = false;
        this.note.textContent = '';
        this.next = 0;   // ask for the next one straight away
      } else {
        const answer = await response.json().catch(() => ({}));
        this.image.hidden = true;
        this.note.textContent = answer.error || 'No camera picture.';
        this.number = 0;
        this.next = Date.now() + 1000;   // nothing yet: ask again in a second
      }
    } catch {
      this.image.hidden = true;
      this.note.textContent = 'Not connected to the robot.';
      this.next = Date.now() + 1000;
    } finally {
      this.busy = false;
    }
  }
}
