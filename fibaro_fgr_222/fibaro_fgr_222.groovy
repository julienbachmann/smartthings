metadata {
    definition (name: "Fibaro FGR-222", namespace: "julienbachmann", author: "Julien Bachmann") {
        capability "Sensor"
        capability "Actuator"
        
		capability "Switch"
        capability "Switch Level"
        
        capability "Polling"
        capability "Power Meter"
        capability "Energy Meter"
        capability "Refresh"
        capability "Configuration"

        attribute "openCloseStatus", "enum", ["open", "middle", "close"]
        
        command "open"
        command "close"
        
        
        fingerprint inClusters: "0x26,0x32"        

    }

    tiles(scale: 2) {
        multiAttributeTile(name:"mainTitle", type:"generic", width:6, height:4, canChangeIcon: true) {
            tileAttribute("device.openCloseStatus", key: "PRIMARY_CONTROL") {
	            attributeState "open", label:'Open', backgroundColor:"#00A000"
	            attributeState "middle", label:'Middle', backgroundColor:"#FFF68F"
	            attributeState "close", label:'Close', backgroundColor:"#0000A0"                
            }
            tileAttribute("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel", defaultState: true, icon:"st.Home.home9" 
            }            
        }
        valueTile("power", "device.power", width: 2, height: 2) {
            state "default", label:'${currentValue} W'
        }     
        valueTile("energy", "device.energy", width: 2, height: 2) {
            state "default", label:'${currentValue} kWh'
        }
        standardTile("refresh", "device.switch", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
            state "default", label:"Refresh", action:"refresh.refresh", icon:"st.secondary.refresh-icon"
        }        
        standardTile("reset", "device.energy", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
            state "default", action:"configuration.configure", icon:"st.secondary.configure" 
        }
        main(["mainTitle"])
        details(["mainTitle", "power", "energy", "refresh", "reset"])        
    }
    
    preferences {
        input name: "invert", type: "bool", title: "Invert up/down", description: "Invert up and down actions"        
        input name: "switchType", type: "enum", title: "Switch type", options: ["Momentary", "Toggle", "Single"], description: "The witch type used with this controller", required: true        
        input name: "openOffset", type: "decimal", title: "Open offset", description: "The percentage from which shutter is displayerd as open"        
        input name: "closeOffset", type: "decimal", title: "Close offset", description: "The percentage from which shutter is displayerd as close"                
    }
}

// parse events into attributes
def parse(String description) {
    log.debug("parse ${description}")
    def result = null
    def cmd = zwave.parse(description, [0x20: 1, 0x26: 3, 0x70: 1, 0x32:3])
    if (cmd) {
        result = zwaveEvent(cmd)
        if (result) {
            log.debug("Dispatch events ${result}")
        }
    } else {
        log.debug("Couldn't zwave.parse ${description}")
    }
    result
}

def correctLevel(value) {
    def result = value 
    if (value == "off") {
    	result = 0;
    }
    if (value == "on" ) {
      result = 100;
    }
    if (invert) {
    	result = 100 - result
    }
    return result
}

def createOpenCloseStatusEvent(value) {
	def theOpenCloseStatus = "middle"
    if (value >= (openOffset ?: 95)) {
	    theOpenCloseStatus = "open"
    }
    if (value <= (closeOffset ?: 5)) {    
	    theOpenCloseStatus = "close"    
    }
	return createEvent(name: "openCloseStatus", value: theOpenCloseStatus, isStateChange: true)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	logger.debug("basic report ${cmd}")
    def result = []
    if (cmd.value) {
    	def level = correctLevel(cmd.value)
        result << createEvent(name: "level", value: level, unit: "%", isStateChange: true)
		result << createOpenCloseStatusEvent(level)
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	logger.debug("basic set ${cmd}")
    def result = []
    if (cmd.value) {
    	def level = correctLevel(cmd.value)
    	result << createEvent(name: "level", value: level, unit: "%", isStateChange: true)
   		result << createOpenCloseStatusEvent(level)
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.commands.switchmultilevelv3.SwitchMultilevelStopLevelChange cmd) {
	log.debug("switch stop event ${cmd}")
}

def zwaveEvent(physicalgraph.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
	log.debug("switch multi level report ${cmd.value}")
    def result = []
    if (cmd.value != null) {
	    def level = correctLevel(cmd.value)
    	result << createEvent(name: "level", value: level, unit: "%", isStateChange: true)
	   	result << createOpenCloseStatusEvent(level)
    }
    return result
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    log.debug("other event ${cmd}")
}

def zwaveEvent(physicalgraph.zwave.commands.meterv3.MeterReport cmd) {
    if (cmd.meterType == 1) {
        if (cmd.scale == 0) {
            return createEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kWh")
        } else if (cmd.scale == 1) {
            return createEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kVAh")
        } else if (cmd.scale == 2) {
            return createEvent(name: "power", value: Math.round(cmd.scaledMeterValue), unit: "W")
        } else {
            return createEvent(name: "electric", value: cmd.scaledMeterValue, unit: ["pulses", "V", "A", "R/Z", ""][cmd.scale - 3])
        }
    }
}

def on() {
	open()
}

def off() {
	close()
}

def open() {
	setLevel(100)
}

def close() {
	setLevel(0)
}

def poll() {
    delayBetween([
        zwave.meterV2.meterGet(scale: 0).format(),
        zwave.meterV2.meterGet(scale: 2).format(),
    ], 1000)
}

def refresh() {
    log.debug("refresh")
    delayBetween([    
    	zwave.switchBinaryV1.switchBinaryGet().format(),
        zwave.switchMultilevelV3.switchMultilevelGet().format(),
        zwave.meterV2.meterGet(scale: 0).format(),
        zwave.meterV2.meterGet(scale: 2).format(),
    ], 500)
}

def setLevel(level) {
    if (invert) {
    	level = 100 - level
    }
    if(level > 99) level = 99
    log.debug("set level ${level}")
    delayBetween([
        zwave.basicV1.basicSet(value: level).format(),
        zwave.switchMultilevelV1.switchMultilevelGet().format()
    ], 10000)
}

def configure() {
    log.debug("configure roller shutter")
    def switchTypeValue = 0
    if (switchType == "Toggle") {
    	switchTypeValue = 1
    }
    else if (switchType == "Single") {
    	switchTypeValue = 2
    }
    log.debug("Init switch type with ${switchTypeValue}")
    delayBetween([    
        zwave.configurationV1.configurationSet(parameterNumber: 14, size: 1, scaledConfigurationValue: switchTypeValue).format(),             
        zwave.configurationV1.configurationSet(parameterNumber: 29, size: 1, scaledConfigurationValue: 1).format(),  // start calibration            
        zwave.switchMultilevelV1.switchMultilevelGet().format(),
        zwave.meterV2.meterGet(scale: 0).format(),
        zwave.meterV2.meterGet(scale: 2).format(),
    ], 500)   
}