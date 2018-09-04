/**
 *  Copyright 2015 SmartThings
 *  Modifications Copyright 2018 Enchantech
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
 *  Enchantech Z-Wave Door/Window Sensor
 *
 *  Author: Johnathon Stevens
 *  Date: 2018-08-14
 */

metadata {
	definition (name: "Enchantech Z-Wave Door/Window Sensor wTEST", namespace: "enchantech", author: "johnathon", ocfDeviceType: "x.com.st.d.sensor.contact") {
		capability "Contact Sensor"
		capability "Sensor"
		capability "Tamper Alert"
		capability "Battery"
		capability "Configuration"
		capability "Health Check"
		command "clearTamper"


		fingerprint mfr:"0214", Prod:"0003", model:"0001", deviceJoinName: "Enchantech Door/Window Sensor"

	}

	// simulator metadata
	simulator {
		// status messages
		status "open":  "command: 3003, payload: FF 0A"
		status "closed": "command: 3003, payload: 00 0A"
		status "wake up": "command: 8407, payload: "
		status "Tamper": "command: 3003, payload: FF 08 "
		status "Close Tamper": "command: 3003, payload: 00 08 "
	}

	// UI tile definitions
	tiles(scale: 2) {
		multiAttributeTile(name:"contact", type: "generic", width: 6, height: 4){
			tileAttribute ("device.contact", key: "PRIMARY_CONTROL") {
				attributeState("open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#e86d13")
				attributeState("closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#00A0DC")
			}
		}
		
		standardTile("tamper", "device.tamper", width: 3, height: 3, inactiveLabel: false, decoration: "flat") {
			state "detected", label:'TAMPER', icon:"st.security.alarm.alarm", backgroundColor:"#e86d13", action:"clearTamper"
			state "clear", label:'No Tamper', icon:"st.security.alarm.clear", backgroundColor:"#ffffff"
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat",width: 3, height: 3) {
			state "battery", label:'${currentValue}% battery', unit:""
		}

		main "contact"
		details(["contact", "tamper", "battery"])
	}
}

def parse(String description) {
	def result = null
	if (description.startsWith("Err 106")) {
		if (state.sec) {
			log.debug description
		} else {
			result = createEvent(
				descriptionText: "This sensor failed to complete the network security key exchange. If you are unable to control it via SmartThings, you must remove it from your network and add it again.",
				eventType: "ALERT",
				name: "secureInclusion",
				value: "failed",
				isStateChange: true,
			)
		}
	} else if (description != "updated") {
		def cmd = null
		cmd = zwave.parse(description, [0x30: 2, 0x80: 1, 0x84: 1])
		if (cmd) {
			log.debug("CMD = $cmd")
			result = zwaveEvent(cmd)
			log.debug ("Result = $result")
		}
	}
	log.debug "parsed '$description' to $result"
	return result
}

def installed() {
	// Device-Watch simply pings if no device events received for 482min(checkInterval)
	sendEvent(name: "checkInterval", value: 2 * 4 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

def updated() {
	// Device-Watch simply pings if no device events received for 482min(checkInterval)
	sendEvent(name: "checkInterval", value: 2 * 4 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
	def cmds = []
	if (!state.lastbat) {
		cmds = []
	} else {
		cmds = [zwave.wakeUpV1.wakeUpNoMoreInformation().format()]
	}
	response(cmds)
}

def configure() {
	commands([
		zwave.sensorBinaryV2.sensorBinaryGet(sensorType: zwave.sensorBinaryV2.SENSOR_TYPE_DOOR_WINDOW),
		zwave.manufacturerSpecificV2.manufacturerSpecificGet()
	], 1000)
}

def clearTamper() {
	if(state.tamperOpen != "open") {
		log.debug "TAMPER Cleared"
		state.tamper = "clear"
		sendEvent(name: "tamper", value: "clear", descriptionText: "$device.displayName Tamper cleared.", isStateChange: true)
	}
}

def tamperValueEvent(value) {
	log.debug "Tamper Change Detected"
	if (value) {
		state.tamper = "detected"
		state.tamperOpen = "open"
		createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName battery compartment is open.", isStateChange: true)
	} else {
		state.tamperOpen = "closed"
		createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName battery compartment is closed. Tamper not cleared.")
	}
}

def sensorValueEvent(value) {
	log.debug "Open/Close Detected"
	if (value) {
		createEvent(name: "contact", value: "open", descriptionText: "$device.displayName is open")
	} else {
		createEvent(name: "contact", value: "closed", descriptionText: "$device.displayName is closed")
	}
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd)
{
	log.debug "running"
	log.debug "Payload $cmd"
	if(cmd.sensorType == 8) {
		tamperValueEvent(cmd.sensorValue)
	} else {
		sensorValueEvent(cmd.sensorValue)
	}
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd)
{
	log.debug "6"
	def event = createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)
	def cmds = []

	if (device.currentValue("contact") == null) { // Incase our initial request didn't make it
		cmds << command(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: zwave.sensorBinaryV2.SENSOR_TYPE_DOOR_WINDOW))
	}

	if (!state.lastbat || now() - state.lastbat > 53*60*60*1000) {
		cmds << command(zwave.batteryV1.batteryGet())
	} else { // If we check the battery state we will send NoMoreInfo in the handler for BatteryReport so that we definitely get the report
		cmds << zwave.wakeUpV1.wakeUpNoMoreInformation().format()
	}

	[event, response(cmds)]
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	log.debug "7"
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "${device.displayName} has a low battery"
		map.isStateChange = true
	} else {
		map.value = cmd.batteryLevel
	}
	state.lastbat = now()
	[createEvent(map), response(zwave.wakeUpV1.wakeUpNoMoreInformation())]
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	log.debug "12"
	createEvent(descriptionText: "$device.displayName: $cmd", displayed: false)
}

private command(physicalgraph.zwave.Command cmd) {
	log.debug "13"
	if (state.sec == 1) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private commands(commands, delay=200) {
	delayBetween(commands.collect{ command(it) }, delay)
}