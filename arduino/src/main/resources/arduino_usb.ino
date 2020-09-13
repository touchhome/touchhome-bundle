#include <printf.h>
#include <EEPROM.h>
#include "SparkFunBME280.h"
#include "Buffer.h"
#include "RTCTimer2.h"
#include <DHT2.h>

// Spark fun block initialization
BME280 bme280Sensor;
DHT2 dht(DHT22);

#define CLEAR_EEPROM       0
#define HARDCODE_DEVICE_ID 1
#define DEBUG_ERROR        1
#define HANDLER_SIZE      80
#define MAX_HANDLER_SIZE  12
#define OFFSET_WRITE_INDEX 9

// Handlers definitions
#define INVERT_PIN           0
#define SET_PIN              1
#define SET_PIN_N_SECONDS    2
#define INVERT_PIN_N_SECONDS 3
#define REQUEST_PIN          4
#define PLAY_TONE            5

#define PIN_REQUEST_TYPE_DHT               1
#define PIN_REQUEST_TYPE_SPARK_FUN_BME280  2

/*
   Payload protocol
   payload[0] = 0x24
   payload[1] = 0x24
   payload[2] = writtenLength (1)
   payload[3] = crc
   payload[4] = crc
   payload[5] = messageID
   payload[6] = deviceID
   payload[7] = deviceID
   payload[8] = commandID

   payload[9] = content
   payload[xx]= content
*/

// handlers command sizes
uint8_t FIRST_LEVEL_SIZES[]{
        1, // INVERT_PIN
        2, // SET_PIN
        3, // SET_PIN_N_SECONDS
        2, // INVERT_PIN_N_SECONDS
        4, // REQUEST_PIN
        3  // PLAY_TONE
};
// End handlers definitions

const uint8_t EEPROM_CONFIGURED = 4;
const uint8_t TIMEOUT_BETWEEN_REGISTER = 10; // seconds

const uint8_t ARDUINO_DEVICE_TYPE = 17;

const uint8_t EXECUTED = 0;
const uint8_t FAILED_EXECUTED = 1;

const uint8_t REGISTER_COMMAND = 10;
const uint8_t REGISTER_SUCCESS_COMMAND = 11;

const uint8_t GET_ID_COMMAND = 20;
const uint8_t GET_TIME_COMMAND = 21;

const uint8_t SET_PIN_VALUE_ON_HANDLER_REQUEST_COMMAND = 30;
const uint8_t GET_PIN_VALUE_COMMAND = 31;

const uint8_t SET_PIN_DIGITAL_VALUE_COMMAND = 40;
const uint8_t SET_PIN_ANALOG_VALUE_COMMAND = 41;

const uint8_t RESPONSE_COMMAND = 50;
const uint8_t PING = 51;

const uint8_t GET_PIN_VALUE_REQUEST_COMMAND = 60;
const uint8_t REMOVE_GET_PIN_VALUE_REQUEST_COMMAND = 61;

const uint8_t HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN = 70;
const uint8_t REMOVE_HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN = 71;


uint32_t modes = 0; // store pin modes

uint8_t settedUpValuesByHandler[21]; // uses for setPin

struct ArduinoConfig {
    unsigned int deviceID;
} arduinoConfig;

uint64_t uniqueID = 0;

uint8_t handler_buf[MAX_HANDLER_SIZE]; // assume we have no data more than 12 bytes
uint8_t handler_buf_2[MAX_HANDLER_SIZE];

uint8_t handlers[HANDLER_SIZE]; // TODO: INCREASE SIZE AND SET DEBUG as 0
uint8_t genID = 0;
unsigned long lastPing = 0;
unsigned long MAX_PING_BEFORE_REMOVE_DEVICE = 600000; // 10 minutes

RTCTimer2 timer;

unsigned int calcCRC(uint8_t messageID, uint8_t commandID, uint8_t length, uint8_t peekStartIndex) {
    //PRINTF("\nCRC: %d, %d, %d\n", messageID, commandID, length);
    unsigned int crc = messageID + arduinoConfig.deviceID + commandID;
    for (uint8_t i = peekStartIndex; i < peekStartIndex + length; i++) {
        char ch = peekCharAbsolute(i); // char. not uint8_t!
        crc += abs(ch); // [from current index to length]
    }
    unsigned int finalCRC = (0xbeaf + crc) & 0x0FFFF;
    return finalCRC;
}

void writeMessage(uint8_t messageID, uint8_t commandID, uint8_t writtenLength) {
    // CRC
    unsigned int crc = calcCRC(messageID, commandID, writtenLength, OFFSET_WRITE_INDEX);
    resetAll();

    // header
    writeByte(0x24);
    writeByte(0x24);
    // length
    writeByte(writtenLength);
    writeUInt(crc);
    // messageID
    writeByte(messageID);
    // device ID
    writeUInt(arduinoConfig.deviceID);
    // Command ID
    writeByte(commandID);
    writeTail();

    Serial.write(payload, 32);
    beginWrite(); // reset written length to 0
}

void sendNoPayloadCallback(uint8_t messageID, uint8_t commandID, uint8_t CMD) {
    beginWrite();
    writeByte(commandID);
    writeMessage(messageID, CMD, getCurrentIndex());
}

void sendResponse(uint8_t messageID, uint8_t commandID, uint8_t value) {
    beginWrite();
    writeByte(commandID);
    writeByte(value);
    writeMessage(messageID, RESPONSE_COMMAND, getCurrentIndex());
}

void sendResponseULong(uint8_t messageID, uint8_t commandID, unsigned long value) {
    beginWrite();
    writeByte(commandID);
    writeULong(value);
    writeMessage(messageID, RESPONSE_COMMAND, getCurrentIndex());
}

void sendResponseUInt(uint8_t messageID, uint8_t commandID, unsigned int value) {
    beginWrite();
    writeByte(commandID);
    writeUInt(value);
    writeMessage(messageID, RESPONSE_COMMAND, getCurrentIndex());
}

void sendSuccessCallback(uint8_t messageID, uint8_t commandID) {
    sendNoPayloadCallback(messageID, commandID, EXECUTED);
}

void sendErrorCallback(uint8_t messageID, uint8_t commandID) {
    sendNoPayloadCallback(messageID, commandID, FAILED_EXECUTED);
}

uint8_t readPinID(uint8_t messageID, uint8_t cmd) {
    uint8_t pinID = readByte(); // [0] pinID A0-14,A1-15,A2-16,A3-17,A4-18,A5-19,A6-20,A7-21
    if (pinID < 0 || pinID > 21) {
        pinID = static_cast<uint8_t>(-1);
        sendErrorCallback(messageID, cmd);
    }
    return pinID;
}

// LOW - 0, HIGH - 1
boolean setDigitalValue(uint8_t pinID, uint8_t value) {
    if (value == LOW || value == HIGH) {
        setOutputMode(pinID);
        digitalWrite(pinID, value);
        return true;
    }
    return false;
}

boolean setAnalogValue(uint8_t pinID, uint8_t value) {
    setOutputMode(pinID);
    analogWrite(pinID, value);
    return true;
}

void setOutputMode(uint8_t pinID) {
  if(bitRead(modes, pinID) == 0) {
    pinMode(pinID, OUTPUT);
    bitWrite(modes, pinID, 1);
  }
}

bool setUniqueReadAddressCommand(uint8_t messageID, uint8_t cmd) {
    uint64_t id = readULong();
    uniqueID = id;
    lastPing = millis();
    sendSuccessCallback(messageID, cmd);
    return true;
}

uint8_t getPinValue(uint8_t pinID) {
    if (pinID < 14) {
        return digitalRead(pinID);
    }
    return map(analogRead(pinID), 0, 1023, 0, 255);
}

uint8_t *subArray(int from, int length, bool firstBuffer, const uint8_t srcArray[]) {
    if (MAX_HANDLER_SIZE < length) {
        return nullptr;
    }
    uint8_t *res = firstBuffer ? handler_buf : handler_buf_2;
    uint8_t destIndex = 0;
    for (int i = from; i < from + length; i++) {
        res[destIndex] = srcArray[i];
        destIndex++;
    }
    return res;
}

uint8_t *getNextHandler(int index, uint8_t &sizeOfHandler) {
    uint8_t handlerType = handlers[index];
    if (handlerType == HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN) {
        uint8_t firstLevelHandler = handlers[index + 4];
        sizeOfHandler = 5 + FIRST_LEVEL_SIZES[firstLevelHandler];
        return subArray(index, sizeOfHandler, true, handlers);
    }
    return nullptr;
}

bool handlersMatch(uint8_t handlerType, const uint8_t existedHandler[], uint8_t existedHandlerSize,
                   const uint8_t handler[], uint8_t handlerSize) {
    if (existedHandlerSize != handlerSize + 1 || existedHandler[0] != handlerType)
        return false;
    for (uint8_t i = 0; i < handlerSize; i++)
        if (existedHandler[i + 1] != handler[i])
            return false;
    return true;
}

/**
   top level handler types:
   WHEN_VALUE_COMPARE_TO_VALUE       // 4: i.e.: [4, pinID, pinValue, [op, pinMode], 1-level handler] Description: [op, pinMode] - '>D':0,'<D':1, '=D':2, '>A':3, '<A':4, '=A':5
      1 - level handler types:
          INVERT_PIN                 // 0 [pinID]
          SET_PIN                    // 1 [pinID, value]
          SET_PIN_N_SECONDS          // 2 [pinID, value, seconds]
          INVERT_PIN_N_SECONDS       // 3 [pinID, seconds]
          REQUEST_PIN                // 5 [pinID]
          PLAY_TONE                  // 5 [pinID, frequency, duration]
**/
void addHandler(uint8_t handlerType, uint8_t handler[], uint8_t handlerSize) {
    int index = 0;
    uint8_t sizeOfHandler = 0;
    uint8_t *existedHandler = getNextHandler(index, sizeOfHandler);
    while (existedHandler != nullptr) {
        bool match = handlersMatch(handlerType, existedHandler, sizeOfHandler, handler, handlerSize);
        if (match) {
            return;
        }
        index += sizeOfHandler;
        existedHandler = getNextHandler(index, sizeOfHandler);
    }
    if (index + handlerSize + 1 > HANDLER_SIZE) {
        return;
    }
    // here index is index for next slot
    uint8_t j = 0;
    handlers[index++] = handlerType;
    for (int i = index; i < index + handlerSize; i++)
        handlers[i] = handler[j++];
}

void removeHandler(uint8_t handlerType, uint8_t handler[], uint8_t handlerSize) {
    int index = 0;
    uint8_t sizeOfHandler = 0;
    uint8_t *existedHandler = getNextHandler(index, sizeOfHandler);
    while (existedHandler != nullptr) {
        bool match = handlersMatch(handlerType, existedHandler, sizeOfHandler, handler, handlerSize);

        if (match) {
            for (int i = index; i < HANDLER_SIZE - handlerSize - 1; i++) {
                handlers[i] = handlers[i + handlerSize + 1];
            }
            return;
        }
        index += sizeOfHandler;
        existedHandler = getNextHandler(index, sizeOfHandler);
    }
}

void requestPinValueDHT(uint8_t pinID, uint8_t handlerID) {
   dht.begin(pinID); // if pinID already begin - dht just ignore this call
   float h = dht.readHumidity();
   float t = dht.readTemperature();

   beginWrite();
   writeByte(handlerID);
   writeFloat(isnan(h) ? 0 : h);
   writeFloat(isnan(t) ? 0 : t);
   writeMessage(0, SET_PIN_VALUE_ON_HANDLER_REQUEST_COMMAND, getCurrentIndex());
}

void requestPinValueBME280(uint8_t pinID, uint8_t handlerID) {
   float h = bme280Sensor.readFloatHumidity();
   float t = bme280Sensor.readTempC();
  // float p = bme280Sensor.readFloatPressure();

   beginWrite();
   writeByte(handlerID);
   writeFloat(isnan(h) ? 0 : h);
   writeFloat(isnan(t) ? 0 : t);
   //writeFloat(isnan(p) ? 0 : p);
   writeMessage(0, SET_PIN_VALUE_ON_HANDLER_REQUEST_COMMAND, getCurrentIndex());
}

void requestPinFunc(uint32_t ts, uint8_t pinID, uint8_t handlerID, uint8_t pinRequestType) {
    switch (pinRequestType) {
        case PIN_REQUEST_TYPE_DHT:
             requestPinValueDHT(pinID, handlerID);

            break;
        case PIN_REQUEST_TYPE_SPARK_FUN_BME280:
            requestPinValueBME280(pinID, handlerID);
            break;
        default:
            // send unique handlerID and simple value
            beginWrite();
            writeByte(handlerID);
            writeByte(getPinValue(pinID));
            writeMessage(0, SET_PIN_VALUE_ON_HANDLER_REQUEST_COMMAND, getCurrentIndex());
    }
}

void readPinValueRequestCommand(uint8_t messageID, uint8_t cmd, bool remove) {
    uint8_t pinID = readPinID(messageID, cmd);
    if (pinID != -1) {
        uint8_t val = readByte();
        uint8_t handlerID = readByte();
        uint8_t pinRequestType = readByte();
        int interval = val == 0 ? 1 : val * 10;
        if(remove) {
            timer.cancelBy2Params(pinID, handlerID, pinRequestType);
        } else {
            timer.everyTwo(interval, requestPinFunc, pinID, handlerID, pinRequestType);
        }
    }
}

bool executeCommandInternally(uint8_t messageID, unsigned int target, uint8_t cmd) {
    uint8_t pinID;
    if (uniqueID == 0) {
        if (target != arduinoConfig.deviceID) {
            return false;
        }
        if (cmd == REGISTER_SUCCESS_COMMAND) {
            return setUniqueReadAddressCommand(messageID, cmd);
        }
        return false;
    }

    if (target == 0 || target == arduinoConfig.deviceID) {
        switch (cmd) {
            case EXECUTED:
                sendErrorCallback(messageID, cmd);
                break;
            case FAILED_EXECUTED:
                sendErrorCallback(messageID, cmd);
                break;
            case REGISTER_COMMAND:
                sendErrorCallback(messageID, cmd);
                break;
            case REGISTER_SUCCESS_COMMAND:
                sendErrorCallback(messageID, cmd);
                break;
            case GET_ID_COMMAND:
                sendResponseUInt(messageID, cmd, arduinoConfig.deviceID);
                break;
            case GET_TIME_COMMAND:
                sendResponseULong(messageID, cmd, millis());
                break;
            case GET_PIN_VALUE_COMMAND:
                getPinValueCommand(messageID, cmd);
                break;
            case SET_PIN_DIGITAL_VALUE_COMMAND:
                setDigitalValue(readPinID(messageID, cmd), readByte());
                break;
            case SET_PIN_ANALOG_VALUE_COMMAND:
                setAnalogValue(readPinID(messageID, cmd), readByte());
                break;
            case PING:
                lastPing = millis();
                beginWrite();
                writeMessage(messageID, PING, getCurrentIndex());
                break;
            case HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN:
                handlerRequestWhenPinValueOpThan(messageID, cmd);
                break;
            case REMOVE_GET_PIN_VALUE_REQUEST_COMMAND:
                readPinValueRequestCommand(messageID, cmd, true);
                break;
            case GET_PIN_VALUE_REQUEST_COMMAND:
                readPinValueRequestCommand(messageID, cmd, false);
                break;
            case REMOVE_HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN:
                removeHandlerWhenPinValueOpThan(messageID, cmd);
                break;
            default:
                return false;
        }
        return true;
    }
    return false;
}

void getPinValueCommand(uint8_t messageID, uint8_t cmd) {
    uint8_t pinID = readPinID(messageID, cmd);
    uint8_t analog = readByte();
    uint8_t value = analog == 1 ? map(analogRead(pinID), 0, 1023, 0, 255) : digitalRead(pinID);
    sendResponse(messageID, cmd, value);
}

void handlerRequestWhenPinValueOpThan(uint8_t messageID, uint8_t cmd) {
    uint8_t pinID = readPinID(messageID, cmd);
    if (pinID > -1) {
        uint8_t handlerSize = getFirstLevelHandlerSize();
        if(handlerSize > -1) {
            uint8_t *handler = subArray(OFFSET_WRITE_INDEX, handlerSize, false, getUPayload());
            //printBuffer(handlerSize, handler);
            if(handler == nullptr) {
               return false;
            }
            addHandler(HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN, handler, handlerSize);
            EEPROM.put(1, arduinoConfig);
        }
    }
}

void removeHandlerWhenPinValueOpThan(uint8_t messageID, uint8_t cmd) {
    uint8_t pinID = readPinID(messageID, cmd);
    if (pinID > -1) {
        uint8_t handlerSize = getFirstLevelHandlerSize();
        if(handlerSize > -1) {
            uint8_t *handler = subArray(OFFSET_WRITE_INDEX, handlerSize, false, getUPayload());
            removeHandler(HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN, handler, handlerSize);
            EEPROM.put(1, arduinoConfig);
        }
    }
}

uint8_t getFirstLevelHandlerSize() {
  uint8_t firstLevelType = peekCharAbsolute(OFFSET_WRITE_INDEX + 3);
  if(firstLevelType > sizeof(FIRST_LEVEL_SIZES)) {
    // sendErrorCallback
    return -1;
  }
  return 4 + FIRST_LEVEL_SIZES[firstLevelType];
}

boolean executeCommand(uint8_t expectedMessageID) {
    resetAll(); // reset indexes for reading
    if (!(readChar() == 0x25 && (readChar() == 0x25))) {
        return false;
    }
    char length = readChar(); // 2
    int crc = readUInt(); // 3
    char messageID = readChar(); // 5
    unsigned int target = readUInt(); // 6
    char commandID = readChar(); // 8

    unsigned int calcCrc = calcCRC(messageID, commandID, length, getCurrentIndex()); // start calc CRC from current position - getWrittenLength();

    if (expectedMessageID != 255 && expectedMessageID != messageID) {
        return false;
    }
    if (target != arduinoConfig.deviceID) {
        return false;
    }
    if (calcCrc != crc) {
        return false;
    }
    return executeCommandInternally(messageID, target, commandID);
}


boolean readMessage() {
    if (Serial.available()) {
        boolean done = false;
        Serial.readBytes(getPayload(), 32);
        return true;
    }
    return false;
}

// Wait here until we get a response, or timeout (250ms)
boolean readMessageNSeconds(int seconds) {
    unsigned long started_waiting_at = millis();
    boolean timeout = false;
    while (!Serial.available() && !timeout) {
        if (millis() - started_waiting_at > seconds * 1000) {
            timeout = true;
        }
        delay(200);
    }
    return readMessage();
}

void clearPinHandlers() {
    for (int i = 0; i < HANDLER_SIZE; i++)
        handlers[i] = 255;
}

void setup() {
    Serial.begin(9600);
    printf_begin();
    setOffsetWrite(OFFSET_WRITE_INDEX);
    if (CLEAR_EEPROM) {
        for (uint8_t i = 0; i < EEPROM.length(); i++) {
            EEPROM.write(i, 0);
        }
    }
    clearPinHandlers();
    bme280Sensor.begin();

    for (uint8_t i = 0; i < 21; i++)
        settedUpValuesByHandler[i] = 255;

    if (EEPROM.read(0) == EEPROM_CONFIGURED) {
        EEPROM.get(1, arduinoConfig);
    } else {
        randomSeed(analogRead(0));
        long randNumber = random(99, 65535);
        if (HARDCODE_DEVICE_ID) {
            arduinoConfig.deviceID = 32621;
        } else {
            arduinoConfig.deviceID = abs(int(randNumber));
        }
        EEPROM.put(1, arduinoConfig);
        EEPROM.write(0, EEPROM_CONFIGURED);
    }
}

uint8_t generateID() {
    genID = genID >= 99 ? 0 : genID + 1;
    return genID;
}

void setPinValue(uint8_t pinID, uint8_t value) {
    bool isAnalog = value > 1 ? true : false;
    if (isAnalog) setAnalogValue(pinID, value);
    else setDigitalValue(pinID, value);
}

void registerDevice() {
    if (millis() - lastPing > MAX_PING_BEFORE_REMOVE_DEVICE) { // if device pinged too ago
        uniqueID = 0;
        EEPROM.put(1, arduinoConfig);
        for (uint8_t i = 0; i < 21; i++)
            settedUpValuesByHandler[i] = 255;
        clearPinHandlers();
        // clear all timeouts
        for (uint8_t i = 0; i < 21; i++)
            settedUpValuesByHandler[i] = 255;
    }
    if (uniqueID == 0) {
        uint8_t messageID = 0;
        while (uniqueID == 0) {
            beginWrite();
            writeByte(ARDUINO_DEVICE_TYPE);
            messageID = generateID();
            writeMessage(messageID, REGISTER_COMMAND, getCurrentIndex());

            while (uniqueID == 0 && readMessageNSeconds(5)) { // while because if internet latency
                executeCommand(messageID);
            }
            clearPayload();
            if (uniqueID == 0) {
                //PRINTF("\nWait %ds", TIMEOUT_BETWEEN_REGISTER);
                delay(TIMEOUT_BETWEEN_REGISTER * 1000);
            }
        }
    }
}

uint8_t getInvertedValue(uint8_t pinID) {
    if (pinID < 14) {
        return digitalRead(pinID) == HIGH ? LOW : HIGH;
    }
    return 255 - map(analogRead(pinID), 0, 1023, 0, 255);
}

// invert state
void invertPinTimerFunc(uint32_t ts, uint8_t pinID, uint8_t pinValue, uint8_t ignore2) {
    setPinValue(pinID, pinValue);
    settedUpValuesByHandler[pinID] = false;
}

void invokeDigitalPinHandleCommand(const uint8_t handler[], uint8_t value, uint8_t startCmdIndex, bool isAnalog,
                                   bool isConditionMatch) {
    uint8_t cmd = handler[startCmdIndex];            //4
    uint8_t handlePin = handler[startCmdIndex + 1];  //5
    uint8_t setValue = handler[startCmdIndex + 2];   //6
    switch (cmd) { // command
        case INVERT_PIN_N_SECONDS:
            if (isConditionMatch && settedUpValuesByHandler[handlePin] != 255) {
                settedUpValuesByHandler[handlePin] = 1;
                timer.everyOnceOne(handler[startCmdIndex + 2], invertPinTimerFunc, handlePin, getPinValue(handlePin));
                setPinValue(handlePin, getInvertedValue(handlePin));
            }
            break;
        case INVERT_PIN:
            if (isConditionMatch && settedUpValuesByHandler[handlePin] != 255) {
                setPinValue(handlePin, getInvertedValue(handlePin));
                settedUpValuesByHandler[handlePin] = 1;
            } else if (settedUpValuesByHandler[handlePin] == 1) {
                settedUpValuesByHandler[handlePin] = 255;
                setPinValue(handlePin, getInvertedValue(handlePin));
            }
            break;
        case SET_PIN_N_SECONDS: // uint8_t payload3[] = {CMDID, 3, 123, 4, SET_PIN_N_SECONDS, 9, 1, 2}; // (pin, value, interval)
            if (isConditionMatch && settedUpValuesByHandler[handlePin] == 255) {
                setPinValue(handlePin, setValue);
                settedUpValuesByHandler[handlePin] = 1;
                timer.everyOnceOne(handler[startCmdIndex + 3], invertPinTimerFunc, handlePin,getPinValue(handlePin));
            }
            break;
        case SET_PIN:
            if (isConditionMatch && settedUpValuesByHandler[handlePin] != 255) {
                setPinValue(handlePin, setValue);
                settedUpValuesByHandler[handlePin] = 1;
            } else if (settedUpValuesByHandler[handlePin] == 1) {
                settedUpValuesByHandler[handlePin] = 255;
                setPinValue(handlePin, 0);
            }
            break;
        // TODO: not works WELL
        case PLAY_TONE:
            if (isConditionMatch && settedUpValuesByHandler[handlePin] == 255) {
                unsigned long duration = (handler[startCmdIndex + 3] * 0.5 + 0.5) * 1000;
                int frequency = handlePin * 80 + 80;
                tone(handlePin, frequency, duration);
                settedUpValuesByHandler[handlePin] = 1;
            } else if (settedUpValuesByHandler[handlePin] == 1) {
                noTone(handlePin);
                if(!isConditionMatch) settedUpValuesByHandler[handlePin] = 255;
            }
        case REQUEST_PIN:
            if (isConditionMatch) {
              if(settedUpValuesByHandler[handlePin] == 255) {
                  int interval = setValue == 0 ? 1 : setValue * 10;
                  uint8_t handlerID = handler[startCmdIndex + 3]; // 7
                  uint8_t pinRequestType = handler[startCmdIndex + 3]; // 7

                  requestPinFunc(0, handlePin, handlerID, pinRequestType);
                  uint8_t timerID = timer.everyTwo(interval, requestPinFunc, handlePin, handlerID, pinRequestType);

                  settedUpValuesByHandler[handlePin] = timerID;
              }
            } else {
                if(settedUpValuesByHandler[handlePin] != 255) {
                    timer.cancel(settedUpValuesByHandler[handlePin]); // cancel timer with index from array
                    settedUpValuesByHandler[handlePin] = 255;
                }
            }
            break;
    }
}

// 0 - >D, 1 - <D, 2 - =D, 3 - >A, 4 - <A, 5 - =A
void HandleFunc_WhenValueOpValue(uint8_t handler[]) {
    // when pin '3' op '>' than value 243 - set pin 13 as HIGH
    // existedHandler - HANDLER_TYPE, {3, 243, 0, SET_PIN, 13, 29};
    uint8_t pinID = handler[1];
    uint8_t val = handler[2];
    uint8_t op = handler[3];
    bool isAnalog = val > 2;
    uint8_t pinValue = isAnalog ? map(analogRead(pinID), 0, 1023, 0, 255) : digitalRead(pinID);
    bool match = (op % 3 == 0 && pinValue > val) ||
                 (op % 3 == 1 && pinValue < val) ||
                 (op % 3 == 2 && pinValue == val);
    invokeDigitalPinHandleCommand(handler, pinValue, 4, isAnalog, match);
}

typedef void (*HandlerFunc)(uint8_t *handler);

HandlerFunc HandlerFunctions[1] = {&HandleFunc_WhenValueOpValue};

void handlePins() {
    int index = 0;
    uint8_t sizeOfHandler = 0;
    uint8_t *existedHandler = getNextHandler(index, sizeOfHandler);
    while (existedHandler != nullptr) {
        HandlerFunctions[existedHandler[0] - HANDLER_REQUEST_WHEN_PIN_VALUE_OP_THAN](existedHandler);
        index += sizeOfHandler;
        existedHandler = getNextHandler(index, sizeOfHandler);
    }
}

void loop() {
    registerDevice();

    // try send pin values
    handlePins();

    while (readMessageNSeconds(1)) {
        executeCommand(255);
        clearPayload();
    }

    // not sure it's best implementation
    timer.update();
}

float readFloat() {
    copy(getPayload(), floatBuf, getCurrentIndex(), 0, 6);
    float value = atof(floatBuf);
    addToCurrentIndex(6);
    return value;
}

void writeFloat(float value) {
    dtostrf(value, 6, 2, floatBuf);
    copy(floatBuf, getPayload(), 0, getCurrentIndex() + OFFSET_WRITE_INDEX, 8);
    addToCurrentIndex(8);
}