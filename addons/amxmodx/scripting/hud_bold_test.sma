/*
 * hud_bold_test.sma
 *
 * Test plugin: shows a normal HUD message (show_hudmessage) and a director
 * HUD message (show_dhudmessage) on screen at the same time so the bold
 * rendering of DHUD can be compared against the thin normal HUD text.
 *
 * Trigger it with:
 *   - console command: hudtest
 *   - say            : /hudtest
 * It also re-shows both messages automatically every few seconds.
 */
#include <amxmodx>

#define MSG_INTERVAL 3.0

public plugin_init()
{
	register_plugin("Hud Bold Test", "1.0", "Pickle")

	register_clcmd("say /hudtest", "cmd_hudtest")
	register_clcmd("hudtest", "cmd_hudtest")
	register_concmd("hudtest", "cmd_hudtest")

	set_task(MSG_INTERVAL, "task_show_messages", .flags = "a")
}

public cmd_hudtest(id)
{
	client_print(id, print_chat, "[HudTest] HUD ve DHUD mesajlari ekranda gosteriliyor.")
	ShowBothMessages()
	return PLUGIN_HANDLED
}

public task_show_messages()
{
	ShowBothMessages()
}

ShowBothMessages()
{
	set_hudmessage(240, 240, 240, -1.0, 0.30, 0, 6.0, 2.5, 0.1, 0.2, 1)
	show_hudmessage(0, "HUD  : BU NORMAL HUD [show_hudmessage]")

	set_dhudmessage(240, 110, 110, -1.0, 0.34, 0, 6.0, 2.5, 0.1, 0.2)
	show_dhudmessage(0, "DHUD : BU KALIN OLMALI [show_dhudmessage]")
}