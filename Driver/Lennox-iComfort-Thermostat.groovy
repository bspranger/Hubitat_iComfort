/**
 *  Lennox iComfort Thermostat
 *
 *  Copyright 2015 Jason Mok
 *
 *  GitHub Link
 *  https://raw.githubusercontent.com/bspranger/Hubitat_iComfort/master/Driver/Lennox-iComfort-Thermostat.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Last updated : 1/05/2019 by Brian Spranger
 * 
 */
 metadata {
	definition (name: "Lennox iComfort Thermostat", namespace: "bspranger", author: "Brian Spranger") {
		capability "Thermostat"
		capability "Relative Humidity Measurement"
		capability "Polling"
		capability "Refresh"
		capability "Sensor"
		capability "Temperature Measurement"
		capability "Actuator"
	        
		attribute "thermostatProgram", "string"
		attribute "presence", "string"
	        
		command "heatLevelUp"
		command "heatLevelDown"
		command "coolLevelUp"
		command "coolLevelDown"
		command "switchMode"
		command "switchFanMode"
		command "switchProgram"
		command "setThermostatProgram"
		command "away"
		command "present"
		command "setPresence"
		command "updateThermostatData", ["string"]
	}
	
	preferences {
		input "isDebugEnabled", "bool", title: "Enable Debugging?", defaultValue: false
	}
}

def parse(String description) { }

def refresh() { parent.refresh() }

def poll() { updateThermostatData(parent.getDeviceStatus(this.device)) }

def updateThermostatData(thermostatData) {		
    def ValChanged = false
	def thermostatProgramSelection
	def thermostatProgramMode = (device.currentValue("thermostatProgram") == "Manual")?"0":"1"
	def thermostatMode = (device.currentState("thermostatMode")?.value)?device.currentState("thermostatMode")?.value:"auto"
    logDebug "UpdateThermostatData: " + thermostatData
	thermostatData.each { name, value -> 
		if (name == "temperature" || name == "coolingSetpoint" || name == "heatingSetpoint") {
			sendEvent(name: name, value: value , unit: getTemperatureScale())
			logDebug "Sending Event: " + [name, value, getTemperatureScale()]
		} else if (name == "thermostatProgramMode") {
			thermostatProgramMode = value
            ValChanged = true
		} else if (name == "thermostatProgramSelection") {
			thermostatProgramSelection = value
            ValChanged = true
		} else if (name == "thermostatMode") {
			thermostatMode = value
            ValChanged = true
		} else if (name == "awayMode") {
			def awayMode = (value == "1") ? "away" : "present"
			if (device.currentValue("presence") != awayMode) {
				sendEvent(name: "presence", value: awayMode)
                logDebug "Sending Event: " + ["presence", awayMode]
			}
		} else {
			sendEvent(name: name, value: value, displayed: false)
			logDebug "Sending Misc Event: " + [name, value]
		}
	}

	if (true == ValChanged){
        if (thermostatProgramMode == "0") {
            sendEvent(name: "thermostatMode", value: thermostatMode)
            sendEvent(name: "thermostatProgram", value: "Manual")
            logDebug "Sending Event: " + ["thermostatMode", thermostatMode]
            logDebug "Sending Event: " + ["thermostatProgram", "Manual"]			
        } else {
            sendEvent(name: "thermostatMode", value: "program") 
            logDebug "Sending Event: " + ["thermostatMode", "program"]
            if (thermostatProgramSelection) {
                sendEvent(name: "thermostatProgram", value: parent.getThermostatProgramName(this.device, thermostatProgramSelection).toString().replaceAll("\r","").replaceAll("\n",""))
                logDebug "Sending Event: " + ["thermostatProgram", parent.getThermostatProgramName(this.device, thermostatProgramSelection).replaceAll("\r","").replaceAll("\n","")]
            }
        }
    }
}

def setHeatingSetpoint(Number heatingSetpoint) {
	// define maximum & minimum for heating setpoint 
	def minHeat = parent.getSetPointLimit(this.device, "heatingSetPointLow")
	def maxHeat = parent.getSetPointLimit(this.device, "heatingSetPointHigh")
	def diffHeat = parent.getSetPointLimit(this.device, "differenceSetPoint").toInteger()
	heatingSetpoint = (heatingSetpoint < minHeat)? minHeat : heatingSetpoint
	heatingSetpoint = (heatingSetpoint > maxHeat)? maxHeat : heatingSetpoint

	// check cooling setpoint 
	def heatSetpointDiff = parent.getTemperatureNext(heatingSetpoint, diffHeat)
	def coolingSetpoint = device.currentValue("coolingSetpoint")
	coolingSetpoint = (heatSetpointDiff > coolingSetpoint)? heatSetpointDiff : coolingSetpoint   
	setThermostatData([coolingSetpoint: coolingSetpoint, heatingSetpoint: heatingSetpoint])
}

def setCoolingSetpoint(Number coolingSetpoint) { 
	// define maximum & minimum for cooling setpoint 
	def minCool = parent.getSetPointLimit(this.device, "coolingSetPointLow")
	def maxCool = parent.getSetPointLimit(this.device, "coolingSetPointHigh")
	def diffHeat = parent.getSetPointLimit(this.device, "differenceSetPoint").toInteger()
	coolingSetpoint = (coolingSetpoint < minCool)? minCool : coolingSetpoint
	coolingSetpoint = (coolingSetpoint > maxCool)? maxCool : coolingSetpoint
	
	// check heating setpoint 
	def coolSetpointDiff = parent.getTemperatureNext(coolingSetpoint, (diffHeat * -1))
	def heatingSetpoint = device.currentValue("heatingSetpoint")
	heatingSetpoint = (coolSetpointDiff < heatingSetpoint)? coolSetpointDiff : heatingSetpoint
	setThermostatData([coolingSetpoint: coolingSetpoint, heatingSetpoint: heatingSetpoint])
}

def switchMode() {
	def currentMode = device.currentState("thermostatMode")?.value
	switch (currentMode) {
		case "off":
			setThermostatMode("heat")
			break
		case "heat":
			setThermostatMode("cool")
			break
		case "cool":
			setThermostatMode("auto")
			break
		case "auto":
			setThermostatMode("program")
			break
		case "program":
			setThermostatMode("off")
			break
		default:
			setThermostatMode("auto")
	}
	if(!currentMode) { setThermostatMode("auto") }
}

def off()           { setThermostatMode("off") }
def heat()          { setThermostatMode("heat") }
def emergencyHeat() { setThermostatMode("emergency heat") }
def cool()          { setThermostatMode("cool") }
def auto()          { setThermostatMode("auto") }

def switchFanMode() {
	def currentFanMode = device.currentState("thermostatFanMode")?.value
	switch (currentFanMode) {
		case "auto":
			setThermostatFanMode("on")
			break
		case "on":
			setThermostatFanMode("off")
			break
        case "off":
			setThermostatFanMode("circulate")
			break
		case "circulate":
			setThermostatFanMode("auto")
			break
		default:
			setThermostatFanMode("auto")
	}
	if(!currentFanMode) { setThermostatFanMode("auto") }
}

def setThermostatMode(mode) { 
	def thermostatProgramMode = (device.currentValue("thermostatProgram") == "Manual")?"0":"1"
	if (thermostatProgramMode != "0") {
		parent.setProgram(this.device, "0", state.thermostatProgramSelection)
		setThermostatData([ thermostatProgramMode: "0", thermostatMode: mode ])
	} else {
		setThermostatData([ thermostatMode: mode ])
	}
}
def fanOff()       { setThermostatFanMode("off")}
def fanOn()        { setThermostatFanMode("on") }
def fanAuto()      { setThermostatFanMode("auto") }
def fanCirculate() { setThermostatFanMode("circulate") }
def setThermostatFanMode(fanMode) {	setThermostatData([ thermostatFanMode: fanMode ]) }
def heatLevelUp() {	
	def heatingSetpoint = device.currentValue("heatingSetpoint")
	setHeatingSetpoint(parent.getTemperatureNext(heatingSetpoint, 1))   
}
def heatLevelDown() { 
	def heatingSetpoint = device.currentValue("heatingSetpoint")
	setHeatingSetpoint(parent.getTemperatureNext(heatingSetpoint, -1))  
}
def coolLevelUp() { 
	def coolingSetpoint = device.currentValue("coolingSetpoint")
	setCoolingSetpoint(parent.getTemperatureNext(coolingSetpoint, 1))  
}
def coolLevelDown() { 
	def coolingSetpoint = device.currentValue("coolingSetpoint")
	setCoolingSetpoint(parent.getTemperatureNext(coolingSetpoint, -1))  
}

def switchProgram() {
	def currentProgram = device.currentValue("thermostatProgram")
	def nextProgramID = parent.getThermostatProgramNext(this.device, currentProgram)
	setThermostatProgram(nextProgramID)
}

def setThermostatProgram(programID) {
	updateThermostatData([thermostatProgramMode: "1", thermostatProgramSelection: programID])
	def thermostatResult = parent.setProgram(this.device, "1", programID)
	updateThermostatData(thermostatResult)
}

def setThermostatData(thermostatData) {
	updateThermostatData(thermostatData)
	def thermostatResult = parent.setThermostat(this.device, thermostatData)
	updateThermostatData(thermostatResult)
}

def away() { setPresence("away") } 
def present() { setPresence("present") } 
def switchPresenceMode() {
	def currentPresenceMode = device.currentState("awayMode")?.value
	switch (currentPresenceMode) {
		case "present":
			setPresence("away")
			break
		case "away":
			setPresence("present")
			break
		default:
			setPresence("present")
	}
}

def setPresence(awayStatus) {
	def awayMode = (awayStatus.toString().equals("away"))?1:0
	updateThermostatData([awayMode: awayMode.toString()])
    logDebug "Calling setAway:" + this.device + " " + awayStatus + " " + awayMode.toString()
	def thermostatResult = parent.setAway(this.device, awayStatus)
	updateThermostatData(thermostatResult)   
}

private logDebug(msg) {
	if (isDebugEnabled != false) {
		log.debug "$msg"
	}
}
