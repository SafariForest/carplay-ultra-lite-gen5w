# Changelog

Versions after 1.0.0 are test builds running on one car (Hyundai Ioniq 6, US, firmware 260507). 1.3.3 is published as a test build (an update for a car on 1.0.0); the main release is still 1.0.0.

## 1.3.3 (2026-11-10, in testing)

- **Experiment "Only CarPlay's volume slider in CarPlay"** (off by default): while CarPlay is on the screen, the volume knob and the steering wheel's volume buttons change the volume without the head unit's volume pop-up, so only CarPlay's slider shows.
  - The knob's speed is kept: a fast spin moves as many steps as it does outside CarPlay.
  - CarPlay's slider is told at once when a key changes the volume.
  - The mute button stays the car's own; its pop-up is dismissed straight afterwards. Known issue: the pop-up still flashes for a moment.
  - Anything unexpected leaves the key to the car, so the volume keys keep working as stock.

## 1.2.x (2026-11-10, in testing)

- **Car volume in CarPlay:** CarPlay's Volume page shows the head unit's real levels for media, navigation, phone and Siri, follows the knob and the steering wheel, and sets the car's volume. The phone can't change a level before it has been shown the car's own one, so nothing jumps.
- **Vehicle notifications:** the Vehicle app's notification list is filled by the car; an entry you dismiss stays dismissed. One test entry is posted at each start.
- **Readings match the cluster:** battery, range and odometer are rounded down as the instrument cluster does.
- CarPlay's Radio app: its play and source buttons open the head unit's own radio (the app itself still has no stations).

## 1.1.x (2026-11-10, in testing)

- **The car's page in CarPlay's Vehicle app:** battery level, range and odometer; a Car home screen button; a Battery preconditioning switch (through Launchpad); the Light Vibes switch and button; buttons for HMG-API, NEMESIS Manager, Radio, EV and Vehicle settings.
- Found on the way: the iPhone builds the Vehicle app from the description it is given when CarPlay connects, so the page is part of that description.

## 1.0.0 (2026-11-08)

- Climate in CarPlay, both ways, with a panel for each seat.
- The CarPlay Ultra theme with its wallpapers and Themes page.
