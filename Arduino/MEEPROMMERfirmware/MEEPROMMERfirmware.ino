/**
 * ** project Arduino EEPROM programmer **
 *
 * This sketch can be used to read and write data to a
 * AT28C64, AT28C256, SST27SF512, W27E512 parallel EEPROM
 *
 * $Author: mario $
 * $Date: 2013/07/31 13:00:00 $
 * $Revision: 1.4 $
 *
 * This software is freeware and can be modified, reused or thrown away without any restrictions.
 *
 * Use this code at your own risk. I'm not responsible for any bad effect or damages caused by this software!!!
 *
 **/

#if defined(__AVR_ATmega328P__)
#define UNO
#elif defined(__AVR_ATmega32U4__)
#define LEONARDO
#endif

#define VERSIONSTRING "MEEPROMMER $Revision: 1.4 $ $Date: July 31st, 2013 1:00pm $, CMD:B,b,w,W,V,C,E,P,p"

// eeprom stuff
// define the IO lines for the data - bus
#define D0 2
#define D1 3
#define D2 4
#define D3 5
#define D4 6
#define D5 7
#define D6 8
#define D7 9

// for high voltage programming supply 
#define VH     10
#define BOOST  11
#define VPP    12

// shiftOut part
#define DS     A0
#define LATCH  A1
#define CLOCK  A2

// define the IO lines for the eeprom control
#define CE     A3
#define OE     A4
#define WE     A5

// direct access to port
#if defined(UNO)
#define STROBE_PORT PORTC
#define STROBE_DS      0
#define STROBE_LATCH   1
#define STROBE_CLOCK   2
#define STROBE_CE      3
#define STROBE_OE      4
#define STROBE_WE      5
#elif defined(LEONARDO)
#define STROBE_PORT PORTF
#define STROBE_DS      7
#define STROBE_LATCH   6
#define STROBE_CLOCK   5
#define STROBE_CE      4
#define STROBE_OE      1
#define STROBE_WE      0
#endif

//a buffer for bytes to burn
#define BUFFERSIZE 1024
byte buffer[BUFFERSIZE];
//command buffer for parsing commands
#define COMMANDSIZE 32
char cmdbuf[COMMANDSIZE];
 
unsigned int startAddress,endAddress;
unsigned int lineLength,dataLength;

#define CHIP28C64 0
#define CHIP28C256 2
#define CHIP27SF512 3

unsigned int chipType;

//define COMMANDS
#define NOCOMMAND    0
#define VERSION      1
 
#define READ_HEX    10
#define READ_BIN    11
#define READ_ITL    12
 
#define WRITE_HEX   20
#define WRITE_BIN   21
#define WRITE_ITL   22
 
#define CHIP_TYPE   30
#define CHIP_ERASE  31
#define VPP_ON      32
#define VPP_OFF     33

/****************************************************************
 *
 *  CONTROL and DATA functions
 *
 ****************************************************************/
 
// switch IO lines of databus to INPUT state
void data_bus_input() {
  pinMode(D0, INPUT);
  pinMode(D1, INPUT);
  pinMode(D2, INPUT);
  pinMode(D3, INPUT);
  pinMode(D4, INPUT);
  pinMode(D5, INPUT);
  pinMode(D6, INPUT);
  pinMode(D7, INPUT);
}
 
//switch IO lines of databus to OUTPUT state
void data_bus_output() {
  pinMode(D0, OUTPUT);
  pinMode(D1, OUTPUT);
  pinMode(D2, OUTPUT);
  pinMode(D3, OUTPUT);
  pinMode(D4, OUTPUT);
  pinMode(D5, OUTPUT);
  pinMode(D6, OUTPUT);
  pinMode(D7, OUTPUT);
}
 
//set databus to input and read a complete byte from the bus
//be sure to set data_bus to input before
byte read_data_bus()
{
#if defined(UNO)
  return (PIND >> 2) | ((PINB & 0x3) << 6);
#elif defined(LEONARDO)
  return (PIND & 0x02) >> 1 | (PIND & 0x01) << 1 | (PIND & 0x10) >> 2 | (PINC & 0x40) >> 3 |
         (PIND & 0x80) >> 3 | (PINE & 0x40) >> 1 | (PINB & 0x30) << 2;
#else
  return ((digitalRead(D7) << 7) |
    (digitalRead(D6) << 6) |
    (digitalRead(D5) << 5) |
    (digitalRead(D4) << 4) |
    (digitalRead(D3) << 3) |
    (digitalRead(D2) << 2) |
    (digitalRead(D1) << 1) |
    digitalRead(D0));
#endif
}
 
//write a byte to the data bus
//be sure to set data_bus to output before
inline void write_data_bus(byte data)
{
#if defined(UNO)
  //2 bits belong to PORTB and have to be set separtely
  PORTB = (PORTB & 0xF8) | (data >> 6);
  //bit 0 to 6 belong to bit 2 to 8 of PORTD
  PORTD = data << 2;
#elif defined(LEONARDO)
  PORTB = (PORTB & 0x8F) | (data & 0xC0) >> 2;
  PORTC = (PORTC & 0xBF) | (data & 0x08) << 3;
  PORTD = (PORTD & 0x6C) | (data & 0x01) << 1 | (data & 0x02) >> 1 |
          (data & 0x04) << 2 | (data & 0x10) << 3 | (data & 0x40) >> 3;
  PORTE = (PORTE & 0xBF) | (data & 0x20) << 1;
#else
  digitalWrite(D0, data & 1);
  digitalWrite(D1, data & 2);
  digitalWrite(D2, data & 4);
  digitalWrite(D3, data & 8);
  digitalWrite(D4, data & 16);
  digitalWrite(D5, data & 32);
  digitalWrite(D6, data & 64);
  digitalWrite(D7, data & 128);
#endif
}


//faster shiftOut function then normal IDE function
#if (defined(UNO) || defined (LEONARDO))
#define FAST_SHIFT(data) { \
  /*--- Turn data on or off based on value of bit*/ \
  if (data & 0x80) \
    bitSet(STROBE_PORT,STROBE_DS); \
  else \
    bitClear(STROBE_PORT,STROBE_DS); \
  data <<= 1; \
  /*register shifts bits on upstroke of clock pin*/ \
  bitSet(STROBE_PORT,STROBE_CLOCK); \
  bitClear(STROBE_PORT,STROBE_CLOCK); \
}
#else
#define FAST_SHIFT(data) { \
  /*--- Turn data on or off based on value of bit*/ \
  if (data & 0x80) \
    digitalWrite(DS, true); \
  else \
    digitalWrite(DS, false); \
  data <<= 1; \
  /*register shifts bits on upstroke of clock pin*/ \
  digitalWrite(CLOCK, true); \
  digitalWrite(CLOCK, false); \
}
#endif

//shift out the given address to the 74hc595 registers
inline void set_address_bus(unsigned int address)
{
  byte hi, low;

  //get high - byte of 16 bit address
  if (chipType == CHIP27SF512) {
    hi = (address >> 8) & 0x3F;
    hi |= (address >> 9) & 0x40;
#if defined(UNO) || defined (LEONARDO)
    if (address & 0x4000)
        bitSet(STROBE_PORT, STROBE_WE);
    else
        bitClear(STROBE_PORT, STROBE_WE);
#else
    digitalWrite(WE, address & 0x4000 ? HIGH : LOW);
#endif
  } else {
    hi = address >> 8;
  }

  //get low - byte of 16 bit address
  low = address & 0xff;

  //shift out highbyte
  FAST_SHIFT(hi); FAST_SHIFT(hi); FAST_SHIFT(hi); FAST_SHIFT(hi);
  FAST_SHIFT(hi); FAST_SHIFT(hi); FAST_SHIFT(hi); FAST_SHIFT(hi);
  //shift out lowbyte
  FAST_SHIFT(low); FAST_SHIFT(low); FAST_SHIFT(low); FAST_SHIFT(low);
  FAST_SHIFT(low); FAST_SHIFT(low); FAST_SHIFT(low); FAST_SHIFT(low);
 
#if defined(UNO) || defined (LEONARDO)
  //strobe latch line
  bitSet(STROBE_PORT,STROBE_LATCH);
  bitClear(STROBE_PORT,STROBE_LATCH);
#else
  digitalWrite(LATCH, true);
  digitalWrite(LATCH, false);
#endif
}
 
//short function to set the OE(output enable line of the eeprom)
// attention, this line is LOW - active
inline void set_oe (byte state)
{
  digitalWrite(OE, state);
}
 
//short function to set the CE(chip enable line of the eeprom)
// attention, this line is LOW - active
inline void set_ce (byte state)
{
  digitalWrite(CE, state);
}
 
//short function to set the WE(write enable line of the eeprom)
// attention, this line is LOW - active
inline void set_we (byte state)
{
  switch (chipType) {
    case CHIP27SF512:
      break;
    default:
      digitalWrite(WE, state);
    }
}

//short function to set the VPP(programming voltage)
// attention, this line is HIGH - active
void set_vpp (byte state)
{
  switch (chipType) {
  case CHIP27SF512:
    digitalWrite(VPP, state);
    break;
  default:
    break;
  }
}

void pwm_on(byte value)
{
  //set PWM to 62 kHz
  //attach timer to pin
  //and write duty cycle
#if defined(UNO)
  TCCR2A = 0x83;
  TCCR2B = 0x01;
  OCR2A = value;
#elif defined(LEONARDO)
  TCCR1A = 0x09;
  TCCR1B = 0x09;
  OCR1C = value;
#endif
}

void pwm_off()
{
  //turn off timer
#if defined(UNO)
  TCCR2A = 0x01;
#elif defined(LEONARDO)
  TCCR1A = 0x01;
#endif
}

void boost_supply (boolean state)
{
#if (defined(UNO) || defined(LEONARDO))
  float duty;

  if (!state) {
    pwm_off();
    return;
  }

  switch (chipType) {
  case CHIP27SF512:
    // VPP voltage of 13.2V
    duty = 1 - (5 / (13.2 + 0.6));
    pwm_on(duty * 255.0);
    break;
  default:
    break;
  }
#endif
}

//short function to set the VH(erase voltage)
// attention, this line is HIGH - active
void set_vh (byte state)
{
  switch (chipType) {
  case CHIP27SF512:
    digitalWrite(VH, state);
    break;
  default:
    break;
  }
}

void read_start() {
  //set databus for reading
  data_bus_input();
  //enable chip select
  set_ce(LOW);
  //disable write
  set_we(HIGH);
  //enable output
  set_oe(LOW);
}

void read_end() {
  //disable output
  set_oe(HIGH);
  //disable chip select
  set_ce(HIGH);
}  

//highlevel function to read a byte from a given address
inline byte read_byte(unsigned int address)
{
  //set address bus
  set_address_bus(address);
  //read data
  return read_data_bus();
}
 
void write_start() {
  //first disable output
  set_oe(HIGH);
  //disable write
  set_we(HIGH);
  //set databus to output
  data_bus_output();
}

void write_end() {
  //set databus to input
  data_bus_input();
}

//highlevel function to write a byte to a given address
inline boolean fast_write(unsigned int address, byte data)
{
  static unsigned int lastAddress = 0;
  static byte lastData = 0;
  
  switch (chipType) {
    case CHIP28C64:
    case CHIP28C256:
      //this function uses /DATA polling to get the end of the
      //page write cycle. This is much faster than waiting 10ms
  
      //enable chip select
      set_ce(LOW);
  
      //only wait for write if the address page has changed or chip is 28C64
      if ((lastAddress ^ address) & 0xFFC0 || chipType == CHIP28C64)
      {
        unsigned long startTime = millis();
  
        //poll data until data matches
        data_bus_input();
        set_oe(LOW);
  
        while(lastData != read_data_bus()) {
          //set timeout here longer than JBurn timeout
          if (millis() - startTime > 3000) return false;
        }
        
        set_oe(HIGH);
        delayMicroseconds(1);
        data_bus_output();
      }
  
      //set address and data for write
      set_address_bus(address);
      write_data_bus(data);
      delayMicroseconds(1);
   
      //strobe write
      set_we(LOW);
      set_we(HIGH);
      //disable chip select
      set_ce(HIGH);
  
      lastAddress = address;
      lastData = data;
      break;
      
    case CHIP27SF512:
      //set address and data for write
      set_address_bus(address);
      write_data_bus(data);
      delayMicroseconds(1);
 
      //strobe ce with programming pulse
      set_ce(LOW);
      //delayMicroseconds(20); // for 27SF512
      delayMicroseconds(100); // for W27E512, works for 27SF512 also
      set_ce(HIGH);
      delayMicroseconds(1);
      break;
  }

  return true;
}

/*************************************************
 *
 * COMMAND and PARSING functions
 *
 *************************************************/
 
//waits for a string submitted via serial connection
//returns only if linebreak is sent or the buffer is filled
void readCommand() {
  //first clear command buffer
  for(int i=0; i< COMMANDSIZE;i++) cmdbuf[i] = 0;
  //initialize variables
  char c = ' ';
  int idx = 0;
  //now read serial data until linebreak or buffer is full
  do {
    if(Serial.available()) {
      c = Serial.read();
      cmdbuf[idx++] = c;
    }
  }
  while (c != '\n' && idx < (COMMANDSIZE)); //save the last '\0' for string end
  //change last newline to '\0' termination
  cmdbuf[idx - 1] = 0;
}
 
//parse the given command by separating command character and parameters
//at the moment only 5 commands are supported
byte parseCommand() {
  //set ',' to '\0' terminator (command string has a fixed strucure)
  //first string is the command character
  cmdbuf[1]  = 0;
  //second string is startaddress (4 bytes)
  cmdbuf[6]  = 0;
  //third string is endaddress (4 bytes)
  cmdbuf[11] = 0;
  //fourth string is length (2 bytes)
  cmdbuf[14] = 0;
  startAddress=hexWord((cmdbuf+2));
  dataLength=hexWord((cmdbuf+7));
  lineLength=hexByte(cmdbuf+12);
  byte retval = 0;
  switch(cmdbuf[0]) {
  case 'R':
    retval = READ_HEX;
    break;
  case 'r':
    retval = READ_BIN;
    break;
  case 'W':
    retval = WRITE_HEX;
    break;
  case 'w':
    retval = WRITE_BIN;
    break;
  case 'V':
    retval = VERSION;
    break;
  case 'C':
    retval = CHIP_TYPE;
    break;
  case 'E':
    retval = CHIP_ERASE;
    break;
  case 'P':
    retval = VPP_ON;
    break;
  case 'p':
    retval = VPP_OFF;
    break;
  default:
    retval = NOCOMMAND;
    break;
  }
 
  return retval;
}
 
char chartohex[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    0, 0, 0, 0, 0, 0, 0,
                    10, 11, 12, 13, 14, 15 };

/************************************************************
 * convert a single hex digit (0-9,a-f) to byte
 * @param char c single character (digit)
 * @return byte represented by the digit
 ************************************************************/
inline byte hexDigit(char c)
{
  return chartohex[c - '0'];
}
 
/************************************************************
 * convert a hex byte (00 - ff) to byte
 * @param c-string with the hex value of the byte
 * @return byte represented by the digits
 ************************************************************/
byte hexByte(char* a)
{
  return ((hexDigit(a[0]) << 4) | hexDigit(a[1]));
}
 
/************************************************************
 * convert a hex word (0000 - ffff) to unsigned int
 * @param c-string with the hex value of the word
 * @return unsigned int represented by the digits
 ************************************************************/
unsigned int hexWord(char* data) {
  return ((hexDigit(data[0]) << 12) |
    (hexDigit(data[1]) << 8) |
    (hexDigit(data[2]) << 4) |
    (hexDigit(data[3])));
}
 
 
/************************************************
 *
 * INPUT / OUTPUT Functions
 *
 *************************************************/
 
 
/**
 * read a data block from eeprom and write out a hex dump
 * of the data to serial connection
 * @param from       start address to read fromm
 * @param to         last address to read from
 * @param linelength how many hex values are written in one line
 **/
void read_block(unsigned int from, unsigned int to, int linelength)
{
  //count the number fo values that are already printed out on the
  //current line
  int               outcount = 0;
  //loop from "from address" to "to address" (included)
  unsigned int address = from;
  read_start();
  do {
    if (outcount == 0) {
      //print out the address at the beginning of the line
      Serial.println();
      Serial.print("0x");
      printAddress(address);
      Serial.print(" : ");
    }
    //print data, separated by a space
    byte data = read_byte(address);
    printByte(data);
    Serial.print(" ");
    outcount = (++outcount % linelength);
  } while (address++ != to);
  read_end();

  //print a newline after the last data line
  Serial.println();
 
}
 
/**
 * read a data block from eeprom and write out the binary data
 * to the serial connection
 * @param from       start address to read fromm
 * @param to         last address to read from
 **/
void read_binblock(unsigned int from, unsigned int to) {
  unsigned int address = from;

  read_start();
  do {
    Serial.write(read_byte(address));
  } while (address++ != to);
  read_end();

  //print out an additional 0-byte as success return code
  Serial.print('\0');
}  
 
/**
 * write a data block to the eeprom
 * @param address  startaddress to write on eeprom
 * @param buffer   data buffer to get the data from
 * @param len      number of bytes to be written
 **/
void write_block(unsigned int address, byte* buffer, int len) {
  write_start();
  for (unsigned int i = 0; i < len; i++) {
    if (fast_write(address++, *buffer++) == false)
      break;
  }
  write_end();
}
 
/**
 * erase entire chip
 **/
void chip_erase() {
  switch (chipType) {
    case CHIP28C64:
      write_start();
      for (unsigned int addr = 0; addr < 0x2000; addr++) {
        if (fast_write(addr, 0xFF) == false)
          break;
      }
      write_end();
      break;
    case CHIP28C256:
      //6 byte sequence to erase
      write_start();
      set_address_bus(0x5555);
      set_we(LOW);
      write_data_bus(0xAA);
      set_we(HIGH);
      set_address_bus(0x2AAA);
      set_we(LOW);
      write_data_bus(0x55);
      set_we(HIGH);
      set_address_bus(0x5555);
      set_we(LOW);
      write_data_bus(0x80);
      set_we(HIGH);
      set_address_bus(0x5555);
      set_we(LOW);
      write_data_bus(0xAA);
      set_we(HIGH);
      set_address_bus(0x2AAA);
      set_we(LOW);
      write_data_bus(0x55);
      set_we(HIGH);
      set_address_bus(0x5555);
      set_we(LOW);
      write_data_bus(0x10);
      set_we(HIGH);
      write_end();
      delay(20);
      break;
    case CHIP27SF512:
      set_ce(HIGH);
      set_oe(HIGH);
      set_address_bus(0x200);
      write_data_bus(0xFF);
      set_vh(HIGH);
      set_vpp(HIGH);
      boost_supply(true);
      delay(1);
      //erase pulse
      set_ce(LOW);
      delay(150);
      set_ce(HIGH);
      delayMicroseconds(1);
      set_vh(LOW);
      set_vpp(LOW);
      boost_supply(false);
      delayMicroseconds(1);
      break;
    default:
      break;
  }
}
 
/**
 * print out a 16 bit word as 4 character hex value
 **/
void printAddress(unsigned int address) {
  if(address < 0x0010) Serial.print("0");
  if(address < 0x0100) Serial.print("0");
  if(address < 0x1000) Serial.print("0");
  Serial.print(address, HEX);
 
}
 
/**
 * print out a byte as 2 character hex value
 **/
void printByte(byte data) {
  if(data < 0x10) Serial.print("0");
  Serial.print(data, HEX);  
}
 
 
 
 
 
/************************************************
 *
 * MAIN
 *
 *************************************************/
void setup() {
  chipType = CHIP28C64;

  //define the shiuftOut Pins as output
  pinMode(DS, OUTPUT);
  pinMode(LATCH, OUTPUT);
  pinMode(CLOCK, OUTPUT);
 
  //define the EEPROM Pins as output
  // take care that they are HIGH
  digitalWrite(BOOST, LOW);
  pinMode(BOOST, OUTPUT);
  digitalWrite(VH, LOW);
  pinMode(VH, OUTPUT);
  digitalWrite(VPP, LOW);
  pinMode(VPP, OUTPUT);
  digitalWrite(OE, HIGH);
  pinMode(OE, OUTPUT);
  digitalWrite(CE, HIGH);
  pinMode(CE, OUTPUT);
  digitalWrite(WE, HIGH);
  pinMode(WE, OUTPUT);

  //set speed of serial connection
//  Serial.begin(115200);
  Serial.begin(460800);
}
 
/**
 * main loop, that runs invinite times, parsing a given command and
 * executing the given read or write requestes.
 **/
void loop() {
  readCommand();
  byte cmd = parseCommand();
  int bytes = 0;
  switch(cmd) {
  case READ_HEX:
    //set a default if needed to prevent infinite loop
    if(lineLength==0) lineLength=32;
    endAddress = startAddress + dataLength -1;
    read_block(startAddress,endAddress,lineLength);
    Serial.println('%');
    break;
  case READ_BIN:
    endAddress = startAddress + dataLength -1;
    read_binblock(startAddress,endAddress);
    break;
  case READ_ITL:
    break;
  case WRITE_BIN:
    //take care for max buffer size
    if(dataLength > 1024) dataLength = 1024;
    endAddress = startAddress + dataLength -1;
    while(bytes < dataLength) {
      if(Serial.available()) buffer[bytes++] = Serial.read();
    }    
    write_block(startAddress,buffer,dataLength);
    delayMicroseconds(150);
    Serial.println('%');
    break;
  case WRITE_HEX:
    break;
  case WRITE_ITL:
    break;
  case VERSION:
    Serial.println(VERSIONSTRING);
    break;
  case CHIP_TYPE:
    chipType = startAddress;
    break;
  case CHIP_ERASE:
    chip_erase();
    Serial.println('%');
    break;
  case VPP_ON:
    set_vpp(HIGH);
    boost_supply(true);
    delay(1);
    break;
  case VPP_OFF:
    set_vpp(LOW);
    boost_supply(false);
    delayMicroseconds(1);
    break;
  default:
    break;    
  }
}

