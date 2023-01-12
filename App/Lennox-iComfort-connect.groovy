/**
 *  Lennox iComfort (Connect)
 *
 *  Copyright 2015 Jason Mok
 *
 *  GitHub Link
 *  https://raw.githubusercontent.com/bspranger/Hubitat_iComfort/master/App/Lennox-iComfort-connect.groovy
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
 *  Last Updated : 1/05/2019 by Brian Spranger
 *
 */
definition(
	name: "Lennox iComfort (Connect)",
	namespace: "bspranger",
	author: "Brian Spranger",
	description: "Connect Lennox iComfort to control your thermostats",
	category: "SmartThings Labs",
	iconUrl:   "http://smartthings.copyninja.net/icons/iComfort@1x.png",
	iconX2Url: "http://smartthings.copyninja.net/icons/iComfort@2x.png",
	iconX3Url: "http://smartthings.copyninja.net/icons/iComfort@3x.png"
)

preferences {
	page(name: "prefLogIn", title: "Lennox iComfort")    
	page(name: "prefListDevice", title: "Lennox iComfort")
}

/* Preferences */
def prefLogIn() {
	def showUninstall = username != null && password != null 
	return dynamicPage(name: "prefLogIn", title: "Connect to Lennox iComfort", nextPage:"prefListDevice", uninstall:showUninstall, install: false) {
		section("Login Credentials"){
			input("username", "text", title: "Username", description: "iComfort Username (case sensitive)")
			input("password", "password", title: "Password", description: "iComfort password (case sensitive)")
		} 
		section("Advanced Options"){
			input "isDebugEnabled", "bool", title: "Enable Debugging?", defaultValue: false
		}            
	}
}

def prefListDevice() {
	def LoginResult = loginCheck()
	if (LoginResult) 
	{
		def thermostatList = getThermostatList()
		if (thermostatList) {
			return dynamicPage(name: "prefListDevice",  title: "Thermostats", install:true, uninstall:true) {
				section("Select which thermostat/zones to use"){
					input(name: "thermostat", type: "enum", required:false, multiple:true, options:[thermostatList])
				}
			}
		} else {
			return dynamicPage(name: "prefListDevice",  title: "Error!", install:false, uninstall:true) {
				section(""){ paragraph "Could not find any devices "  }
			}
		}
	} else {
		return dynamicPage(name: "prefListDevice",  title: "Error!", install:false, uninstall:true) {
			section(""){ paragraph "The username or password you entered is incorrect. Try again. " }
		}  
	}
}

/* Initialization */
def installed() { 
	initialize() 
}
def updated() { 
	unsubscribe()
	initialize() 
}

def uninstalled() {
	unschedule()
	getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}	

def initialize() {    
	// Get initial polling state
	state.polling = [ last: 0, rescheduler: now() ]
    state.troubleshoot = null
    
	// Create selected devices
	def thermostatList = getThermostatList()
	def selectedDevices = [] + getSelectedDevices("thermostat")
	selectedDevices.each { (getChildDevice(it))?:addChildDevice("bspranger", "Lennox iComfort Thermostat", it, null, ["name": "Lennox iComfort: " + thermostatList[it]]) }

	// Remove unselected devices
	def deleteDevices = (selectedDevices) ? (getChildDevices().findAll { !selectedDevices.contains(it.deviceNetworkId) }) : getAllChildDevices()
	deleteDevices.each { deleteChildDevice(it.deviceNetworkId) } 
	
	//Refresh device
	refresh()
	    
	runEvery1Minute(refresh)
}

def getSelectedDevices( settingsName ) { 
	def selectedDevices = [] 
	(!settings.get(settingsName))?:((settings.get(settingsName)?.getAt(0)?.size() > 1)  ? settings.get(settingsName)?.each { selectedDevices.add(it) } : selectedDevices.add(settings.get(settingsName))) 
	return selectedDevices 
} 

/* Access Management */
private loginCheck() { 
	def returnval = 0
	apiPut("/DBAcessService.svc/ValidateUser", [query: [UserName: settings.username, lang_nbr: "1"]] ) { response ->
		if (response.status == 200) {
			if (response.data.msg_code == "SUCCESS")
			{
				returnval = 1
			}
			else
			{
				returnval =  0
			}
		} else {
			returnval = 0
		}
	}
	return returnval
}

// Listing all the thermostats you have in iComfort
private getThermostatList() { 	    
	def thermostatList = [:]
	def gatewayList = [:]
	state.data = [:]
	state.lookup = [
		thermostatOperatingState: [:],
		thermostatFanMode: [:],
		thermostatMode: [:],
		program: [:],
		coolingSetPointHigh: [:],
		coolingSetPointLow: [:],
		heatingSetPointHigh: [:],
		heatingSetPointLow: [:],
		differenceSetPoint: [:],
		temperatureRangeF: [:]
	]
	state.list = [
		temperatureRangeC: [],
		program: [:]
	]
    
	//Get Thermostat Mode lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "Operation_Mode", langnumber: 0]]) { response ->
		logDebug "ThermostatModes: $response"
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatMode.putAt(it.value.toString(), translateDesc(it.description))
		}
	}
	
	//Get Fan Modes lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "Fan_Mode", langnumber: 0]]) { response ->
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatFanMode.putAt(it.value.toString(), translateDesc(it.description))
		}
	}
	
	//Get System Status lookups
	apiGet("/DBAcessService.svc/GetTstatLookupInfo", [query: [name: "System_Status", langnumber: 0]]) { response ->
		response.data.tStatlookupInfo.each {
			state.lookup.thermostatOperatingState.putAt(it.value.toString(), translateDesc(it.description))
		}
	}    

	//Get Temperature lookups
	apiGet("/DBAcessService.svc/GetTemperatureRange", [query: [highpoint: 40, lowpoint: 0]]) { response ->
		response.data.each {
			def temperatureLookup = it.Value.split("\\|")
			state.lookup.temperatureRangeF.putAt(temperatureLookup[1].toString(), temperatureLookup[0].toString())
			state.list.temperatureRangeC.add(temperatureLookup[0].toString())
		}
	}    

	//Retrieve all the gateways
	apiGet("/DBAcessService.svc/GetSystemsInfo", [query: [userID: settings.username]]) { response ->
		if (response.status == 200) {
			response.data.Systems.each { device ->
				gatewayList.putAt(device.Gateway_SN,device.System_Name)
			}
		}
	}   
	//Retrieve all the Zones
	gatewayList.each { gatewaySN, gatewayName ->		
		apiGet("/DBAcessService.svc/GetTStatInfoList", [query: [GatewaySN: gatewaySN, TempUnit: (getTemperatureScale()=="F")?0:1, Cancel_Away: "-1"]]) { response ->
			if (response.status == 200) {
				response.data.tStatInfo.each { 
					def dni = [ app.id, gatewaySN, it.Zone_Number ].join('|')
					thermostatList[dni] = ( it.Zones_Installed > 1 )? gatewayName + ": " + it.Zone_Name : gatewayName
					
					//Get the state of each device
					state.data[dni] = [
						temperature: it.Indoor_Temp,
						humidity: it.Indoor_Humidity,
						coolingSetpoint: it.Cool_Set_Point,
						heatingSetpoint: it.Heat_Set_Point,
						thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
						thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
						thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
						thermostatProgramMode: it.Program_Schedule_Mode,
						thermostatProgramSelection: it.Program_Schedule_Selection,
						awayMode: it.Away_Mode.toString()
					]
					
					//Get Devices Program lookups
					state.lookup.program.putAt(dni, [:])
					state.list.program.putAt(dni, [])
					apiGet("/DBAcessService.svc/GetTStatScheduleInfo", [query: [GatewaySN: gatewaySN]]) { response2 ->
						if (response2.status == 200) {
							response2.data.tStatScheduleInfo.each {
								state.lookup.program[dni].putAt(it.Schedule_Number.toString(), "Program " + (it.Schedule_Number + 1) + ":\n" + it.Schedule_Name)
								state.list.program[dni].add("Program " + (it.Schedule_Number + 1) + ":\n" + it.Schedule_Name)
							}      
						}
					}
                    
					//Get Devices Limit Lookups
					apiGet("/DBAcessService.svc/GetGatewayInfo", [query: [GatewaySN: gatewaySN, TempUnit: "0"]]) { response2 ->
						if (response2.status == 200) {
							state.lookup.coolingSetPointHigh.putAt(dni, response2.data.Cool_Set_Point_High_Limit)
							state.lookup.coolingSetPointLow.putAt(dni, response2.data.Cool_Set_Point_Low_Limit)
							state.lookup.heatingSetPointHigh.putAt(dni, response2.data.Heat_Set_Point_High_Limit)
							state.lookup.heatingSetPointLow.putAt(dni, response2.data.Heat_Set_Point_Low_Limit)
							state.lookup.differenceSetPoint.putAt(dni, response2.data.Heat_Cool_Dead_Band)
						}
					}
				}
			}
		}
	}

	return thermostatList
}

/* api connection */

// HTTP GET call
private apiGet(apiPath, apiParams = [], callback = {}) {	
	// set up parameters
	apiParams = [ uri: getApiURL(), path: apiPath, headers: [Authorization : getApiAuth()], ignoreSSLIssues: true ] + apiParams 
	
	try {
		httpGet(apiParams) { response -> callback(response) }
	} catch (Error e)	{
		logDebug "API Error: $e"
	} catch (java.net.SocketTimeoutException e) {
        log.warn "Lennox connection timed out"
    }
}

// HTTP PUT call
private apiPut(apiPath, apiParams = [], callback = {}) {    
	// set up final parameters
	apiParams = [ uri: getApiURL(), path: apiPath, headers: [Authorization: getApiAuth()], ignoreSSLIssues: true ] + apiParams
	logDebug "apiParams: $apiParams"

	try {
		httpPut(apiParams) { response -> callback(response) }
	} catch (Error e) {
		logDebug "API Error: $e"
	}
}

// update child device data
private updateDeviceChildData(device) {
	apiGet("/DBAcessService.svc/GetTStatInfoList", [query: [GatewaySN: getDeviceGatewaySN(device), TempUnit: (getTemperatureScale()=="F")?0:1, Cancel_Away: "-1"]]) { response ->
		if (response.status == 200) {
			logDebug "updateDeviceChildData: $response.data"
			response.data.tStatInfo.each { 
				def dni = [ app.id, it.GatewaySN, it.Zone_Number ].join('|')
				state.data[dni] = [
					temperature: it.Indoor_Temp,
					humidity: it.Indoor_Humidity,
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
					thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
					thermostatProgramMode: it.Program_Schedule_Mode,
					thermostatProgramSelection: it.Program_Schedule_Selection,
					awayMode: it.Away_Mode.toString()
				]
			}
		}
	}
	return true
}

// lookup value translation
def lookupInfo( lookupName, lookupValue, lookupMode ) {
	//logDebug "State info: $state"
	if (lookupName == "thermostatFanMode") {
		if (lookupMode) {
			return state.lookup.thermostatFanMode.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatFanMode.find{it.value==lookupValue.toString()}?.key
		}
	}
	if (lookupName == "thermostatMode") {
		if (lookupMode) {
			return state.lookup.thermostatMode.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatMode.find{it.value==lookupValue.toString()}?.key
		}	
	}
	if (lookupName == "thermostatOperatingState") {
		if (lookupMode) {
			return state.lookup.thermostatOperatingState.getAt(lookupValue.toString())
		} else {
			return state.lookup.thermostatOperatingState.find{it.value==lookupValue.toString()}?.key
		}	
	}
}

/* for SmartDevice to call */
// Refresh data
def refresh() {
	//log.info "Refreshing data..."
		
	// update data for child devices
	getAllChildDevices().each { (!updateDeviceChildData(it))?:it.updateThermostatData(state.data[it.deviceNetworkId.toString()]) }
}

// Get Device Gateway SN
def getDeviceGatewaySN(childDevice) { return childDevice.deviceNetworkId.toString().split("\\|")[1] }

// Get Device Zone
def getDeviceZone(childDevice) { return childDevice.deviceNetworkId.toString().split("\\|")[2] }

// Get single device status
def getDeviceStatus(childDevice) { return state.data[childDevice.deviceNetworkId.toString()] }

// Send thermostat
def setThermostat(childDevice, thermostatData = []) {
	thermostatData.each { key, value -> 
		if (key=="coolingSetpoint") { state.data[childDevice.deviceNetworkId].coolingSetpoint = value }
		if (key=="heatingSetpoint") { state.data[childDevice.deviceNetworkId].heatingSetpoint = value }
		if (key=="thermostatFanMode") { state.data[childDevice.deviceNetworkId].thermostatFanMode = value }
		if (key=="thermostatMode") { state.data[childDevice.deviceNetworkId].thermostatMode = value }
	}
	
	// set up final parameters
	def apiBody = [ 
		Cool_Set_Point: state.data[childDevice.deviceNetworkId].coolingSetpoint,
		Heat_Set_Point: state.data[childDevice.deviceNetworkId].heatingSetpoint,
		Fan_Mode: lookupInfo("thermostatFanMode",state.data[childDevice.deviceNetworkId].thermostatFanMode.toString(),false),
		Operation_Mode: lookupInfo("thermostatMode",state.data[childDevice.deviceNetworkId].thermostatMode.toString(),false),
		Pref_Temp_Units: (getTemperatureScale()=="F")?0:1,
		Zone_Number: getDeviceZone(childDevice),
		GatewaySN: getDeviceGatewaySN(childDevice) 
	]
	
    apiPut("/DBAcessService.svc/SetTStatInfo", [contentType: "application/x-www-form-urlencoded", requestContentType: "application/json; charset=utf-8", body: apiBody]) 
    
    return state.data[childDevice.deviceNetworkId]
}

// Set program
def setProgram(childDevice, scheduleMode, scheduleSelection) {
	def apiBody = []
	def thermostatData = []
	
	//Retrieve program info
	state.data[childDevice.deviceNetworkId].thermostatProgramMode = scheduleMode
	if (scheduleMode == "1") {
		state.data[childDevice.deviceNetworkId].thermostatProgramSelection = scheduleSelection
		apiGet("/DBAcessService.svc/GetProgramInfo", [query: [GatewaySN: getDeviceGatewaySN(childDevice), ScheduleNum: scheduleSelection, TempUnit: (getTemperatureScale()=="F")?0:1]]) { response ->
			if (response.status == 200) {
				state.data[childDevice.deviceNetworkId].coolingSetpoint = response.data.Cool_Set_Point
				state.data[childDevice.deviceNetworkId].heatingSetpoint = response.data.Heat_Set_Point
				state.data[childDevice.deviceNetworkId].thermostatFanMode = lookupInfo("thermostatFanMode",response.data.Fan_Mode.toString(),true)
			}
		}
	}
		
	// set up final parameters for program
	apiBody = [ 
		Cool_Set_Point: state.data[childDevice.deviceNetworkId].coolingSetpoint,
		Heat_Set_Point: state.data[childDevice.deviceNetworkId].heatingSetpoint,
		Fan_Mode: lookupInfo("thermostatFanMode",state.data[childDevice.deviceNetworkId].thermostatFanMode.toString(),false),
		Operation_Mode: lookupInfo("thermostatMode",state.data[childDevice.deviceNetworkId].thermostatMode.toString(),false),
		Pref_Temp_Units: (getTemperatureScale()=="F")?0:1,
		Program_Schedule_Mode: scheduleMode,
		Program_Schedule_Selection: scheduleSelection,
		Zone_Number: getDeviceZone(childDevice),
		GatewaySN: getDeviceGatewaySN(childDevice) 
	]
	
	//Set Thermostat Program
	apiPut("/DBAcessService.svc/SetProgramInfoNew", [contentType: "application/x-www-form-urlencoded", requestContentType: "application/json; charset=utf-8", body: apiBody]) { response ->
		if (response.status == 200) {
			response.data.tStatInfo.each { 
				state.data[device.deviceNetworkId] = [
					temperature: it.Indoor_Temp,
					humidity: it.Indoor_Humidity,
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
					thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
					thermostatProgramMode: it.Program_Schedule_Mode,
					thermostatProgramSelection: it.Program_Schedule_Selection,
					awayMode: it.Away_Mode.toString()
				]
				thermostatData = [
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
				]
			}
		}
	}
	   
	//Set Thermostat Values
	return setThermostat(childDevice, thermostatData)
}


def translateDesc(value) {
	switch (value) {
		case "cool only"     : return "cool"
		case "heat only"     : return "heat"
		case "heat or cool"  : return "auto"
		default: return value
	}
}


def getThermostatProgramName(childDevice, thermostatProgramSelection) {
	def thermostatProgramSelectionName = state?.lookup?.program[childDevice.deviceNetworkId]?.getAt(thermostatProgramSelection.toString())
	return thermostatProgramSelectionName?thermostatProgramSelectionName:"Unknown"
}

def getThermostatProgramNext(childDevice, value) {
	def sizeProgramIndex = state.list.program[childDevice.deviceNetworkId].size() - 1
	def currentProgramIndex = (state?.list?.program[childDevice.deviceNetworkId]?.findIndexOf { it == value })?state?.list?.program[childDevice.deviceNetworkId]?.findIndexOf { it == value } : 0
	def nextProgramIndex = ((currentProgramIndex + 1) <= sizeProgramIndex)? (currentProgramIndex + 1) : 0
	def nextProgramName = state?.list?.program[childDevice.deviceNetworkId]?.getAt(nextProgramIndex)
	return state?.lookup?.program[childDevice.deviceNetworkId]?.find{it.value==nextProgramName}?.key
}

def getTemperatureNext(value, diffIndex) {
	if (getTemperatureScale()=="F") {
		return (value + diffIndex)
	} else {
		def currentTemperatureIndex = state?.list?.temperatureRangeC?.findIndexOf { it == value.toString() }.toInteger()
		def nextTemperature = new BigDecimal(state?.list?.temperatureRangeC[currentTemperatureIndex + diffIndex])
		return nextTemperature
	}
}

def getSetPointLimit( childDevice, limitType ) { 
	if (getTemperatureScale() == "F") {
		return  state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId) 
	} else {
		if (limitType == "differenceSetPoint") {
			return  state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId)
		} else {
			def limitTemperatureF = state?.lookup?.getAt(limitType)?.getAt(childDevice.deviceNetworkId)
			def limitTemperatureC = new BigDecimal(state?.lookup?.temperatureRangeF?.getAt(limitTemperatureF.toInteger().toString()))
			return limitTemperatureC
		}
	}
}

// Set Away Mode
def setAway(childDevice, awayStatus) {    
	def awayMode = ((awayStatus.toString().equals("away"))?"1":"0")
	//Retrieve program info
	state.data[childDevice.deviceNetworkId].awayMode = awayMode.toString()
	
	def apiQuery = [ 
		awayMode: awayMode.toString(),
		ZoneNumber: getDeviceZone(childDevice),
		TempScale: (getTemperatureScale()=="F")?0:1,
		GatewaySN: getDeviceGatewaySN(childDevice) 
	]
	
	//Set Thermostat Program
	apiPut("/DBAcessService.svc/SetAwayModeNew", [contentType: "application/json; charset=utf-8", requestContentType: "application/json; charset=utf-8", query: apiQuery]) { response ->
		if (response.status == 200) {
			response.data.tStatInfo.each { 
				state.data[childDevice.deviceNetworkId] = [
					temperature: it.Indoor_Temp,
					humidity: it.Indoor_Humidity,
					coolingSetpoint: it.Cool_Set_Point,
					heatingSetpoint: it.Heat_Set_Point,
					thermostatMode: lookupInfo( "thermostatMode", it.Operation_Mode.toString(), true ),
					thermostatFanMode: lookupInfo( "thermostatFanMode", it.Fan_Mode.toString(), true ),
					thermostatOperatingState: lookupInfo( "thermostatOperatingState", it.System_Status.toString(), true ),
					thermostatProgramMode: it.Program_Schedule_Mode,
					thermostatProgramSelection: it.Program_Schedule_Selection,
					awayMode: it.Away_Mode.toString()
				]
			}
		}
	}
	return state.data[childDevice.deviceNetworkId]
}

//API URL
def getApiURL() { 
	def troubleshoot = "false"
	if (settings.troubleshoot == "true") {
		if (!(state.troubleshoot)) state.troubleshoot = now() + 3600000 
		troubleshoot = (state.troubleshoot > now()) ? "true" : "false"
	}
	if (troubleshoot == "true") {
		return "https://services-myicomfort-com-xjor6gavxo3b.runscope.net"
	} else {
		return "https://services.myicomfort.com"
	}
}

//API Authorization header
def getApiAuth() {
	return "Basic " + (settings.username + ":" + settings.password).bytes.encodeBase64()
}

private logDebug(msg) {
	if (isDebugEnabled != false) {
		log.debug "$msg"
	}
}
