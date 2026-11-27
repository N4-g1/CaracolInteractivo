#include <SoftwareSerial.h>
#include <avr/wdt.h>
#include <stdio.h>
#include <string.h>

// Arduino Uno. RX D2 <- HC-05 TX, TX D3 -> divisor de voltaje -> HC-05 RX.
SoftwareSerial radio(2, 3);
constexpr uint8_t MATRIX_DATA = 4;
constexpr uint8_t MATRIX_CLOCK = 5;
constexpr uint8_t MATRIX_CS = 6;
constexpr uint8_t ALERT_LED = 7;
constexpr uint8_t BUZZER = 8;
constexpr uint8_t POT = A0;
constexpr uint16_t THRESHOLD = 512;
constexpr unsigned long HEARTBEAT_TIMEOUT = 3000;
constexpr unsigned long BEEP_MS = 140;

const uint8_t HAPPY[8] = {
  0b00001010, 0b00001110, 0b01101110, 0b10011000,
  0b10111000, 0b10011010, 0b11111100, 0b01111000
};
const uint8_t HIDDEN[8] = {
  0b00000000, 0b00111000, 0b01000100, 0b10000010,
  0b10011010, 0b10001010, 0b01111100, 0b00000000
};

bool armed = false;
bool manualHide = false;
bool hidden = true;
bool alarm = false;
bool beeping = false;
unsigned long lastHeartbeat = 0;
unsigned long beepStartedAt = 0;
unsigned long sequence = 0;
uint16_t raw = 0;
char inputLine[64];
uint8_t inputSize = 0;
bool discardLine = false;

// Stop a watchdog left active by a watchdog reset before the sketch initializes.
void clearWatchdog() __attribute__((naked, used, section(".init3")));
void clearWatchdog() {
  MCUSR = 0;
  wdt_disable();
}

void matrixWrite(uint8_t address, uint8_t value) {
  digitalWrite(MATRIX_CS, LOW);
  shiftOut(MATRIX_DATA, MATRIX_CLOCK, MSBFIRST, address);
  shiftOut(MATRIX_DATA, MATRIX_CLOCK, MSBFIRST, value);
  digitalWrite(MATRIX_CS, HIGH);
}

void drawCaracol(bool hide) {
  const uint8_t *sprite = hide ? HIDDEN : HAPPY;
  for (uint8_t row = 0; row < 8; ++row) matrixWrite(row + 1, sprite[row]);
}

void safeState() {
  armed = false;
  manualHide = false;
  hidden = true;
  alarm = false;
  beeping = false;
  digitalWrite(ALERT_LED, LOW);
  digitalWrite(BUZZER, LOW);
  drawCaracol(true);
}

void updateOutputs(unsigned long now) {
  if (!armed) return;
  const bool newAlarm = raw >= THRESHOLD;
  if (newAlarm && !alarm) {
    beepStartedAt = now;
    beeping = true;
    digitalWrite(BUZZER, HIGH);
  }
  if (beeping && (!newAlarm || now - beepStartedAt >= BEEP_MS)) {
    beeping = false;
    digitalWrite(BUZZER, LOW);
  }
  if (alarm != newAlarm) {
    alarm = newAlarm;
    digitalWrite(ALERT_LED, alarm ? HIGH : LOW);
  }
  const bool nextHidden = manualHide || alarm;
  if (nextHidden != hidden) {
    hidden = nextHidden;
    drawCaracol(hidden);
  }
}

void sendLine(const char *line) {
  radio.print(line);
  radio.write('\n');
}

void sendReading() {
  char response[64];
  snprintf(response, sizeof(response), "T,%lu,%u,%u,%u,%u", ++sequence,
      static_cast<unsigned int>(raw), static_cast<unsigned int>(hidden),
      static_cast<unsigned int>(alarm), static_cast<unsigned int>(armed));
  sendLine(response);
}

void sendAck(uint16_t id, const char *result) {
  char response[29];
  snprintf(response, sizeof(response), "A,%u,%s", id, result);
  sendLine(response);
}

uint16_t parseCommandId(const char *digits) {
  unsigned long id = 0;
  for (uint8_t n = 0; digits[n] != '\0'; ++n) {
    if (n >= 5 || digits[n] < '0' || digits[n] > '9') return 0;
    id = id * 10 + (digits[n] - '0');
  }
  return id <= 60000 ? static_cast<uint16_t>(id) : 0;
}

void processLine(const char *line) {
  const unsigned long now = millis();
  if (strcmp(line, "INICIO") == 0) {
    safeState();
    raw = analogRead(POT);
    lastHeartbeat = now;
    armed = true;
    updateOutputs(now);
    sendLine("LISTO");
    sendReading();
    return;
  }
  if (strcmp(line, "H") == 0) {
    if (armed) lastHeartbeat = now;
    sendReading();
    return;
  }
  const char command = line[0];
  if ((command != 'E' && command != 'M') || line[1] != ',') return;
  const uint16_t id = parseCommandId(line + 2);
  if (id == 0) return;

  if (!armed) {
    sendAck(id, "SEGURO");
  } else if (command == 'M' && raw >= THRESHOLD) {
    sendAck(id, "RUIDO");
  } else {
    manualHide = command == 'E';
    updateOutputs(now);
    sendAck(id, "OK");
  }
  sendReading();
}

void checkSerial() {
  // Bound work per loop so a continuous serial stream cannot starve fail-safe checks.
  for (uint8_t n = 0; n < 16 && radio.available(); ++n) {
    const int value = radio.read();
    if (value == '\n') {
      if (!discardLine && inputSize > 0) {
        inputLine[inputSize] = '\0';
        processLine(inputLine);
      }
      inputSize = 0;
      discardLine = false;
    } else if (value < 32 || value > 126 || inputSize >= sizeof(inputLine) - 1) {
      discardLine = true;
    } else if (!discardLine) {
      inputLine[inputSize++] = static_cast<char>(value);
    }
  }
}

void setup() {
  digitalWrite(ALERT_LED, LOW);
  digitalWrite(BUZZER, LOW);
  digitalWrite(MATRIX_CS, HIGH);
  pinMode(ALERT_LED, OUTPUT);
  pinMode(BUZZER, OUTPUT);
  pinMode(MATRIX_DATA, OUTPUT);
  pinMode(MATRIX_CLOCK, OUTPUT);
  pinMode(MATRIX_CS, OUTPUT);
  matrixWrite(0x0F, 0);
  matrixWrite(0x0C, 0);
  matrixWrite(0x09, 0);
  matrixWrite(0x0B, 7);
  matrixWrite(0x0A, 2);
  safeState();
  matrixWrite(0x0C, 1);
  radio.begin(9600);
  sendLine("BOOT");
  wdt_enable(WDTO_2S);
}

void loop() {
  wdt_reset();
  const unsigned long now = millis();
  raw = analogRead(POT);
  if (armed && now - lastHeartbeat >= HEARTBEAT_TIMEOUT) {
    safeState();
    sendReading();
  }
  updateOutputs(now);
  checkSerial();
}
