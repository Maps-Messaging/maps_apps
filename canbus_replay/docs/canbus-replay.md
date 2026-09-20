# MAPS CAN Bus Replay

`maps-canbus-replay` replays recorded CAN bus events to a SocketCAN interface. It is intended for repeatable testing of MAPS CAN/N2K processing without requiring the original live bus.

## Installation

Install the `maps-apps` Debian package. The command and its section 1 manual page are installed with the package.

```bash
man maps-canbus-replay
```

## Use

Prepare the required SocketCAN interface, then replay a compatible capture using the options documented by the command and manual page.

Use replay only against the intended test interface. CAN traffic can cause real devices to act on received frames, a property engineers traditionally discover at the least convenient possible moment.
