#include "BluetoothSerial.h" // Include the Bluetooth serial library

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Please run `make menuconfig` to and enable it
#endif

#define LED 2     // The pin of the onboard status LED

#define M_0HA 13  // Motor 0 high side A
#define M_0HB 27  // Motor 0 high side B
#define M_0LA 12  // Motor 0 low side A
#define M_0LB 14  // Motor 0 low side B

#define M_1HA 26  // Motor 1 high side A
#define M_1HB 32  // Motor 1 high side B
#define M_1LA 25  // Motor 1 low side A
#define M_1LB 33  // Motor 1 low side B

#define HI_OFF LOW  // State of the I/O pin when the high side MOSFET is off
#define LO_OFF HIGH // State of the I/O pin when the low side MOSFET is off. Low side MOSFETS are contorlled through a NOT gate
#define HI_ON HIGH  // State of the I/O pin when the high side MOSFET is on
#define LO_ON LOW   // State of the I/O pin when the low side MOSFET is on. Low side MOSFETS are contorlled through a NOT gate

#define MOTOR_COUNT 2     // Number of motors on the car
#define PINS_PER_MOTOR 4  // How many pins are used per motor
#define PWM_PER_MOTOR 2   // How many pins are used per motor for PWM
#define LED 2             // The pin of the onboard status LED
#define HEADLIGHTS 23     // The pin the headlights are controlled from

/* Legal states oposite high/low side switch control pins
 * Not following this table will result in shorting the battery through the MOSFETs
 * Hi and Lo are the high and low side mosfets
 * 0 and 1 are the output states of the IO pins, ~ = do not care
 * The values on the left are the state of one MOSFET, the values inside are the safe values for the other MOSFET.
 *   | Hi | Lo
 * 1 | 1  | ~
 * 0 | ~  | 0
 */

static const int motorPins[] = {M_0HA, M_0HB, M_0LA, M_0LB, M_1HA, M_1HB, M_1LA, M_1LB}; // List of all motor pins used to setup pin mode
static const int motorLowPins[] = {M_0LA, M_0LB, M_1LA, M_1LB}; // List of PWM pins used to setup pwm

static const int HI_A[] = {M_0HA, M_1HA}; // List of the high side A pins
static const int HI_B[] = {M_0HB, M_1HB}; // List of the high side B pins
static const int LO_A[] = {M_0LA, M_1LA}; // List of the low side A pins
static const int LO_B[] = {M_0LB, M_1LB}; // List of the low side B pins

// Sets the motor power. ID is the motor ID, power is the requested power, and forwards is the direction with true being forwards
void setMotor(int id, byte power, bool forwards) {
  // Print debug message to USB serial
  Serial.println(power);
  // First turn both high side MOSFETs off so that we can seup the low side without shorting
  digitalWrite(HI_A[id], HI_OFF);
  digitalWrite(HI_B[id], HI_OFF);
  // Allow 1 microsecond for the MOSFET gates to charge
  delayMicroseconds(1);
  if(power < 1) { // If the motor power is 0 or somehow below
    // Turn the low sides off so the car will roll to a stop
    digitalWrite(LO_A[id], LO_OFF); 
    digitalWrite(LO_B[id], LO_OFF);
  } else {
    // Now set the PWM on the low side MOSFETs, remembering that they are controlled through a NOT gate so values are inverted such that 255 is off.
    ledcWrite((id*PWM_PER_MOTOR), (forwards) ? 255 : (255-power));
    ledcWrite((id*PWM_PER_MOTOR)+1, (forwards) ? (255-power) : 255);
    // Turn on the required high side MOSFET and make sure the other one is still off
    digitalWrite(HI_A[id], (forwards) ? HI_ON : HI_OFF);
    digitalWrite(HI_B[id], (forwards) ? HI_OFF : HI_ON);
  }
}

// Initializes the motor IO pins and PWM controllers
void setupMotors() {
  for(int i = 0; i < (MOTOR_COUNT * PINS_PER_MOTOR); i++) { // For each motor pin
    digitalWrite(motorPins[i], LOW);                        // Make sure it will be low when it is activated
    pinMode(motorPins[i], OUTPUT);                          // Set the pin mode to output so it can source/sink current
  }
  for(int i = 0; i < (MOTOR_COUNT * PWM_PER_MOTOR); i++) {  // For each PWM channel
    ledcSetup(i, 30000, 8);                                 // Setup the PWM on that ID with a frequency of 30kHz and a resolution of 8 bits 0 <= X < 256
    ledcAttachPin(motorLowPins[i], i);                      // Attach the PWM controller to the I/O pin
  }
}

// Define the Serial Bluetooth object
BluetoothSerial SerialBT;

// Called when a Bluetooth connection things happen
void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param){
  if(event == ESP_SPP_SRV_OPEN_EVT){          // If a device has connected
    Serial.println("Controller Connected");     // Print debug message
    digitalWrite(LED, HIGH);                    // Turn on the status LED
    digitalWrite(HEADLIGHTS, HIGH);             // Turn on the headlights
  } else if(event == ESP_SPP_CLOSE_EVT ){     // If a device has disconnected
    Serial.println("Controller disconnected");  // Print debug message
    digitalWrite(LED, LOW);                     // Turn off status LED
    digitalWrite(HEADLIGHTS, LOW);              // Turn off headlights
    setMotor(0, 0, false);                      // Turn off motor 0
    setMotor(1, 0 , false);                     // Turn off motor 1
  }
}

void setup() { // put your setup code here, to run once at the beginning
  Serial.begin(115200);                   // Start USB serial for deubg

  SerialBT.register_callback(btCallback); // Setup connect/disconnect actions
  
  SerialBT.begin("ESP32test");            // Begin Bluetooth serial as device name
  Serial.println("The device started, now you can pair it with bluetooth!");
  
  pinMode(LED, OUTPUT);                   // Enable status LED
  pinMode(HEADLIGHTS, OUTPUT);            // Enable headlights
  
  setupMotors();                          // Setup motors
}

char cmd[4]; // Buffer to store the command

void loop() { // Main code to run repeatedly
  if (SerialBT.available()) {             // If there is a bluetooth message
    digitalWrite(LED, HIGH);              // Turn on the status LED, will turn it off when we are done processing the message 
    while(SerialBT.available()) {         // For each byte in the bluetooth message
      char c = (char) SerialBT.read();    // Get the next byte
      switch(c) {                         // Switch depending on the next byte
        case 13:                          // Carrage return, treat it as a newline
        case '\n': {                        // Newline means the end of the message
          int id = -1;                      // Set the next char id to -1
          switch(cmd[0]) {                  // Switch based on the command which should be in byte 0 of the command buffer
          case 'l': id = 0; break;            // If it is for the left motor, set the motor ID to 0
          case 'r': id = 1; break;            // If it is for the right motor, set the motor ID to 1
          }
          bool dir = cmd[1] == '+';           // If the direction byte (byte 1) is "+", set the direction to forwards
          String hex = (String) cmd[2] + (String) cmd[3]; // Bytes 2 and 3 should be the high and low order hex characters representing the speed, so store them together for processing in a moment
          byte sp = strtol(hex.c_str(), NULL, 16);        // Convert the hex string to a byte
          setMotor(id, sp, dir);              // Set the motor based on the paramaters calculated previously
//          Serial.print(id);                 // Uncomment these lines to print motor commands to the serial debug
//          Serial.print(" ");
//          Serial.print(dir);
//          Serial.print(" ");
//          Serial.print(sp, HEX);
//          Serial.println();
        } break;
        default: {                          // If it is not the end of a command
          cmd[0] = cmd[1];                  // Move each byte in the command buffer 1 forward.
          cmd[1] = cmd[2];                  // There is probibly a faster way to do this, but this works well enough
          cmd[2] = cmd[3];
          cmd[3] = c;                       // Store the new byte to the end of the command buffer
//          SerialBT.write(c);              // Uncomment these lines to print all bluetooth incoming bytes to the serial debug
//          Serial.write(c);
        } break;
      }
    }
    digitalWrite(LED, LOW);             // Turn the status LED off, compleating the blink
  }
  delay(1);                             // Delay for 1ms to let the cpu sleep rather than running in circles forever
}
