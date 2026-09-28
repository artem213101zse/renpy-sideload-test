# Зачем этот файл: два экрана LAN-ходов. netplay_lobby только
# подключает, netplay_play открывается после connected и там поле хода.
# Пока связи нет, input хода не создаётся. hello_engine не вызывается.

init python:
    import os
    import netplay_link

    netplay_status = "waiting"
    netplay_ping_ms = "-"
    netplay_last_move = u""
    netplay_moves = []
    netplay_ip = "127.0.0.1"
    netplay_port = netplay_link.PORT
    netplay_ip_input = u""
    netplay_server_ip = u"127.0.0.1"
    netplay_server_port = u"7777"
    netplay_move_input = u""
    netplay_note = u""
    netplay_user_left = False
    netplay_hold_result = False

    def _netplay_text(text):
        if isinstance(text, unicode):
            return text
        try:
            return text.decode("utf-8")
        except Exception:
            return unicode(text, "utf-8", "replace")

    def netplay_log_file():
        android = ("ANDROID_PRIVATE" in os.environ) or ("ANDROID_PUBLIC" in os.environ)
        if android:
            folder = "/storage/emulated/0/Documents/the_question_sideload"
        else:
            folder = os.path.join(renpy.config.basedir, "sideload")
        return os.path.join(folder, "netplay.log")

    def netplay_reset_view():
        store.netplay_status = "waiting"
        store.netplay_ping_ms = "-"
        store.netplay_last_move = u""
        store.netplay_moves = []
        store.netplay_note = u""

    def netplay_arm():
        store.netplay_ip = netplay_link.lan_ip()
        netplay_link.get_link().set_log(netplay_log_file(), store.netplay_ip)

    def netplay_on_show():
        store.netplay_ip = netplay_link.lan_ip()
        link = netplay_link.get_link()
        if link.running():
            return
        link.discard_events()
        if store.netplay_hold_result:
            store.netplay_hold_result = False
            return
        netplay_reset_view()

    def netplay_lobby_hide():
        link = netplay_link.get_link()
        if store.netplay_status == "connected" and link.running():
            return
        link.close(goodbye=u"quit")

    def netplay_play_hide():
        if store.netplay_user_left:
            store.netplay_user_left = False
            return
        link = netplay_link.get_link()
        if link.running():
            link.close(goodbye=u"quit")
        if store.netplay_status == "connected":
            store.netplay_status = "waiting"
        else:
            store.netplay_hold_result = True

    def netplay_start_host():
        netplay_reset_view()
        netplay_arm()
        netplay_link.get_link().host(store.netplay_port)

    def netplay_start_guest():
        netplay_reset_view()
        netplay_arm()
        netplay_link.get_link().connect(
            store.netplay_ip_input, store.netplay_port, role="guest")

    def netplay_start_server():
        netplay_reset_view()
        netplay_arm()
        text = _netplay_text(store.netplay_server_port).strip()
        try:
            port = int(text)
        except Exception:
            port = 0
        link = netplay_link.get_link()
        if port < 1 or port > 65535:
            link._report("server", store.netplay_server_ip, text, "bad port")
            netplay_pump()
            return
        link.connect(store.netplay_server_ip, port, role="server")

    def netplay_resign():
        store.netplay_user_left = True
        netplay_link.get_link().close(goodbye=u"resign")
        store.netplay_status = "waiting"
        store.netplay_ping_ms = "-"

    def netplay_to_lobby():
        store.netplay_user_left = True
        netplay_link.get_link().close(goodbye=u"quit")
        store.netplay_status = "waiting"
        store.netplay_ping_ms = "-"
        store.netplay_note = u""

    def netplay_send_move():
        if store.netplay_status != "connected":
            return
        if not netplay_link.get_link().running():
            return
        text = _netplay_text(store.netplay_move_input).strip()
        text = text.replace(u"\r", u" ").replace(u"\n", u" ").strip()
        if not text:
            return
        netplay_link.get_link().submit(u"move " + text)
        store.netplay_last_move = text
        store.netplay_moves = list(store.netplay_moves) + [u"вы " + text]
        store.netplay_move_input = u""

    def netplay_read_move():
        if store.netplay_status != "connected":
            return
        if not netplay_link.get_link().running():
            return
        entered = renpy.input(u"Ход", default=u"", length=32)
        if not entered:
            return
        store.netplay_move_input = entered
        netplay_send_move()

    def netplay_prompt_move():
        renpy.invoke_in_new_context(netplay_read_move)

    def netplay_pump():
        for line in netplay_link.get_link().drain():
            line = _netplay_text(line)
            if line.startswith(u"status "):
                store.netplay_status = line[7:]
            elif line.startswith(u"rtt "):
                store.netplay_ping_ms = line[4:].strip()
            elif line.startswith(u"note "):
                store.netplay_note = line[5:] + u"\n" + _netplay_text(netplay_log_file())
            elif line.startswith(u"move "):
                move = line[5:].strip()
                if not move:
                    continue
                store.netplay_last_move = move
                store.netplay_moves = list(store.netplay_moves) + [u"соперник " + move]


screen netplay_lobby():

    tag menu

    on "show" action Function(netplay_on_show)
    on "hide" action Function(netplay_lobby_hide)
    timer 0.2 action Function(netplay_pump) repeat True

    if netplay_status == "connected":
        timer 0.05 action ShowMenu("netplay_play")

    use game_menu(_("Netplay"), scroll="viewport"):

        vbox:
            spacing 12

            text "Свой IP [netplay_ip]"
            text "Порт [netplay_port]"
            text "[netplay_status]"
            if netplay_note:
                text netplay_note substitute False

            textbutton _("Хост") default_focus True action Function(netplay_start_host)

            text _("Гость")
            text "IP хоста, не свой"
            input:
                value VariableInputValue("netplay_ip_input")
                length 64
            textbutton _("Connect") action Function(netplay_start_guest)

            text _("Сервер")
            input:
                value VariableInputValue("netplay_server_ip")
                length 64
            input:
                value VariableInputValue("netplay_server_port")
                length 5
            textbutton _("Подключиться к серверу") action Function(netplay_start_server)


screen netplay_play():

    tag menu

    on "hide" action Function(netplay_play_hide)
    timer 0.2 action Function(netplay_pump) repeat True

    if netplay_status != "connected":
        timer 0.05 action ShowMenu("netplay_lobby")

    use game_menu(_("Netplay"), scroll="viewport"):

        vbox:
            spacing 12

            text "[netplay_status]"
            text "Пинг: [netplay_ping_ms] мс"
            if netplay_note:
                text netplay_note substitute False

            text _("Ходы")
            for item in netplay_moves:
                text item substitute False

            if netplay_status == "connected" and renpy.android:
                textbutton _("Ввести ход") action Function(netplay_prompt_move)
            elif netplay_status == "connected":
                text _("Ход")
                input:
                    value VariableInputValue("netplay_move_input")
                    length 32
                textbutton _("Отправить") action Function(netplay_send_move)

            textbutton _("Сдаться") action [ Function(netplay_resign), ShowMenu("netplay_lobby") ]
            textbutton _("В лобби") action [ Function(netplay_to_lobby), ShowMenu("netplay_lobby") ]


translate russian strings:

    old "Netplay"
    new "Сеть"
