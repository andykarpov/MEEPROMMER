/**
 * ** Arduino EEPROM programmer **
 *
 * This sketch can be used to read and write data to a
 * AT28C16, AT28C64, AT28C256 parallel EEPROM
 *
 **/

#define VERSIONSTRING "MEEPROMMER MOD, CMD:A,R,r,w,W,V,C,E,P,p"

#define READ_DELAY 1 // uS
#define WRITE_DELAY 15 // mS

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

// shiftOut part
#define DS     A0
#define LATCH  A1
#define CLOCK  A2

// define the IO lines for the eeprom control
#define CE     A3
#define OE     A4
#define WE     A5

#define LED_WRITE 13
#define LED_READ 12

//a buffer for bytes to burn
#define BUFFERSIZE 1024
byte buffer[BUFFERSIZE];
//command buffer for parsing commands
#define COMMANDSIZE 32
char cmdbuf[COMMANDSIZE];
 
unsigned int startAddress,endAddress;
unsigned int lineLength,dataLength;

#define CHIP28C64 0
#define CHIP28C128 1
#define CHIP28C256 2
#define CHIP28C512 3
#define CHIP28C16 4
#define CHIP28C32 5

unsigned int chipType;

//define COMMANDS
#define NOCOMMAND    0
#define VERSION      1
#define SET_ADDRESS  2

#define READ_HEX    10
#define READ_BIN    11
#define READ_ITL    12
 
#define WRITE_HEX   20
#define WRITE_BIN   21
#define WRITE_ITL   22
 
#define CHIP_TYPE   30
#define CHIP_ERASE  31

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
  return ((digitalRead(D7) << 7) |
    (digitalRead(D6) << 6) |
    (digitalRead(D5) << 5) |
    (digitalRead(D4) << 4) |
    (digitalRead(D3) << 3) |
    (digitalRead(D2) << 2) |
    (digitalRead(D1) << 1) |
    digitalRead(D0));
}
 
//write a byte to the data bus
//be sure to set data_bus to output before
inline void write_data_bus(byte data)
{
  digitalWrite(D0, data & 1);
  digitalWrite(D1, data & 2);
  digitalWrite(D2, data & 4);
  digitalWrite(D3, data & 8);
  digitalWrite(D4, data & 16);
  digitalWrite(D5, data & 32);
  digitalWrite(D6, data & 64);
  digitalWrite(D7, data & 128);
}

//shift out the given address to the 74hc595 registers
inline void set_address_bus(unsigned int address)
{
  int addrlo = address & 0xFF;
  int addrhi = (address >> 8) & 0xFF;
  digitalWrite(LATCH, LOW);
  shiftOut(DS, CLOCK, MSBFIRST, addrhi);
  shiftOut(DS, CLOCK, MSBFIRST, addrlo);
  digitalWrite(LATCH, HIGH);
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
  digitalWrite(WE, state);
}

//short function to set up the programmer for reading
void read_start() {
  digitalWrite(LED_READ, HIGH);
  //set databus for reading
  data_bus_input();
  //enable chip select
  set_ce(LOW);
  //disable write
  set_we(HIGH);
  //enable output
  set_oe(LOW);
}

//short function to stop reading
void read_end() {
  //disable output
  set_oe(HIGH);
  //disable chip select
  set_ce(HIGH);
  digitalWrite(LED_READ, LOW);
}  

//highlevel function to read a byte from a given address
inline byte read_byte(unsigned int address)
{
  //set address bus
  set_address_bus(address);
  delayMicroseconds(1);
  //read data
  return read_data_bus();
}

//flag set if we are on the first write pass
boolean firstWritePass = true;
 
//short function to set up the programmer for writing
void write_start() {
  digitalWrite(LED_WRITE, HIGH);
  firstWritePass = true;
  //first disable output
  set_oe(HIGH);
  //disable write
  set_we(HIGH);
  //set databus to output
  data_bus_output();
}

//short function to stop writing
void write_end() {
  //set databus to input
  data_bus_input();
  digitalWrite(LED_WRITE, LOW);
}

//highlevel function to write a byte to a given address
inline boolean fast_write(unsigned int address, byte data)
{
  static unsigned int lastAddress = 0;
  static byte lastData = 0;
  
  //this function uses /DATA polling to get the end of the
  //page write cycle. This is much faster than waiting 10ms

  //enable chip select
  set_ce(LOW);
  
  /*if (!firstWritePass) {
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
  }*/
  
  //set address and data for write
  set_address_bus(address);
  delayMicroseconds(1);
  write_data_bus(data);
  delayMicroseconds(1);
 
  //strobe write
  set_we(LOW);
  delay(WRITE_DELAY);
  set_we(HIGH);
  //disable chip select
  set_ce(HIGH);

  lastAddress = address;
  lastData = data;
  firstWritePass = false;

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
  case 'A':
    retval = SET_ADDRESS;
    break;
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
  default:
    retval = NOCOMMAND;
    break;
  }
 
  return retval;
}
 
/************************************************************
 * convert a single hex digit (0-9,a-f) to byte
 * @param char c single character (digit)
 * @return byte represented by the digit
 ************************************************************/
inline byte hexDigit(char c)
{
  //use lookup table for char to hex conversion
  const char chartohex[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                    0, 0, 0, 0, 0, 0, 0,
                    10, 11, 12, 13, 14, 15 };
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
  int last_address = 0;
  switch (chipType) {
    case CHIP28C16:
      last_address = 2048;
    break;
    case CHIP28C32:
      last_address = 4096;
    break;
    case CHIP28C64:
      last_address = 8192;
    break;
    case CHIP28C128:
      last_address = 16384;
    break;
    case CHIP28C256:
      last_address = 32768;
    break;
    case CHIP28C512:
      last_address = 65536;
    break;
  }
  write_start();
  for (unsigned int addr = 0; addr < last_address; addr++) {
    if (fast_write(addr, 0xFF) == false)
      break;
  }
  write_end();
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
  //default to 28C64 for backward JBurn compatability
  chipType = CHIP28C64;

  //define the shiuftOut Pins as output
  pinMode(DS, OUTPUT);
  pinMode(LATCH, OUTPUT);
  pinMode(CLOCK, OUTPUT);
 
  //define the EEPROM Pins as output
  // take care that they are HIGH
  digitalWrite(OE, HIGH);
  pinMode(OE, OUTPUT);
  digitalWrite(CE, HIGH);
  pinMode(CE, OUTPUT);
  digitalWrite(WE, HIGH);
  pinMode(WE, OUTPUT);
  
  pinMode(LED_WRITE, OUTPUT);
  pinMode(LED_READ, OUTPUT);
  
  //set speed of serial connection
  //on Leonardo this is noop
  Serial.begin(115200);
//  Serial.begin(460800);
//Serial.begin(9600);
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
  case SET_ADDRESS:
    // Set the address bus to an arbitrary value.
    // Useful for debugging shift-register wiring, byte-order.
    // e.g. A,00FF
    Serial.print("Setting address bus to 0x");
    Serial.println(cmdbuf + 2);
    set_address_bus(startAddress);
    break;
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
  default:
    break;    
  }
}

