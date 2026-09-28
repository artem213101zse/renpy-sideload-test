# Зачем этот файл: экран LAN-шахмат без доски. Поток сокета в
# netplay_link.py, сюда попадают только готовые строки статуса,
# пинга и ходов. hello_engine из этой комнаты не вызывается.

init python:
    import netplay_link

    netplay_status = "waiting"
    netplay_ping_ms = "-"
    netplay_last_move = u""
    netplay_moves = []
    netplay_ip = "127.0.0.1"
    netplay_port = netplay_link.PORT
    netplay_ip_input = u"127.0.0.1"
    netplay_move_input = u""
    netplay_note = u""

    def _netplay_text(text):
        if isinstance(text, unicode):
            return text
        try:
            return text.decode("utf-8")
        except Exception:
            return unicode(text, "utf-8", "replace")

    def netplay_reset_view():
        store.netplay_status = "waiting"
        store.netplay_ping_ms = "-"
        store.netplay_last_move = u""
        store.netplay_moves = []
        store.netplay_note = u""

    def netplay_on_show():
        store.netplay_ip = netplay_link.lan_ip()
        link = netplay_link.get_link()
        if not link.running():
            link.discard_events()
            netplay_reset_view()

    def netplay_on_hide():
        netplay_link.get_link().close(goodbye=u"quit")

    def netplay_start_host():
        netplay_reset_view()
        store.netplay_ip = netplay_link.lan_ip()
        netplay_link.get_link().host(store.netplay_port)

    def netplay_start_guest():
        netplay_reset_view()
        netplay_link.get_link().connect(store.netplay_ip_input, store.netplay_port)

    def netplay_resign():
        netplay_link.get_link().close(goodbye=u"resign")
        store.netplay_status = "waiting"
        store.netplay_ping_ms = "-"

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

    def netplay_pump():
        for line in netplay_link.get_link().drain():
            line = _netplay_text(line)
            if line.startswith(u"status "):
                store.netplay_status = line[7:]
            elif line.startswith(u"rtt "):
                store.netplay_ping_ms = line[4:].strip()
            elif line.startswith(u"note "):
                store.netplay_note = line[5:]
            elif line.startswith(u"move "):
                move = line[5:].strip()
                if not move:
                    continue
                store.netplay_last_move = move
                store.netplay_moves = list(store.netplay_moves) + [u"соперник " + move]


screen netplay():

    tag menu

    on "show" action Function(netplay_on_show)
    on "hide" action Function(netplay_on_hide)
    timer 0.2 action Function(netplay_pump) repeat True

    use game_menu(_("Netplay"), scroll="viewport"):

        vbox:
            spacing 12

            text "Хост [netplay_ip]:[netplay_port]"
            textbutton _("Хост") action Function(netplay_start_host)

            text _("Гость")
            input:
                value VariableInputValue("netplay_ip_input")
                length 64
            textbutton _("Connect") action Function(netplay_start_guest)

            text "[netplay_status]"
            text "Пинг: [netplay_ping_ms] мс"
            if netplay_note:
                text netplay_note substitute False

            text _("Последний ход")
            if netplay_last_move:
                text netplay_last_move substitute False
            else:
                text "-"

            text _("Ходы")
            for item in netplay_moves:
                text item substitute False

            text _("Ход")
            input:
                value VariableInputValue("netplay_move_input")
                length 32
            textbutton _("Отправить") action Function(netplay_send_move)
            textbutton _("Сдаться") action Function(netplay_resign)
            textbutton _("Выйти") action Return()


translate russian strings:

    old "Netplay"
    new "Сеть"
