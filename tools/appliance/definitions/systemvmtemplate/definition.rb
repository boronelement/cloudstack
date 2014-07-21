arch = ENV['VM_ARCH'] || 'i386'

#
# NOTE: Before changing the version of the debian image make
# sure it is added to the userContent of jenkins.buildacloud.org
# and the copy task is updated on the systemvm builds
# This will prevent the inevitable build failure once the iso is
# removed from the debian mirrors
#
architectures = {
    :i386 => {
        :os_type_id => 'Debian',
        :iso_file => 'debian-7.6.0-i386-netinst.iso',
        :iso_src => 'http://cdimage.debian.org/debian-cd/7.6.0/i386/iso-cd/debian-7.6.0-i386-netinst.iso',
        :iso_md5 => '528e1a7315da1bbf50bd4d187880a519',
    },
    :amd64 => {
        :os_type_id => 'Debian_64',
        :iso_file => 'debian-7.6.0-amd64-netinst.iso',
        :iso_src => 'http://cdimage.debian.org/debian-cd/7.6.0/amd64/iso-cd/debian-7.6.0-amd64-netinst.iso',
        :iso_md5 => '8a3c2ad7fd7a9c4c7e9bcb5cae38c135'
    }
}

config = {
    :cpu_count => '1',
    :memory_size => '256',
    :disk_size => '2500', :disk_format => 'VDI', :hostiocache => 'off',
    :iso_download_timeout => '1200',
    :boot_wait => '10',
    :boot_cmd_sequence => [
        '<Esc>',
        'install ',
        'preseed/url=http://%IP%:%PORT%/preseed.cfg ',
        'debian-installer=en_US ',
        'auto ',
        'locale=en_US ',
        'kbd-chooser/method=us ',
        'netcfg/get_hostname=systemvm ',
        'netcfg/get_domain=apache.org ',
        'fb=false ',
        'debconf/frontend=noninteractive ',
        'console-setup/ask_detect=false ',
        'console-keymaps-at/keymap=us ',
        'keyboard-configuration/xkb-keymap=us ',
        '<Enter>'
    ],
    :kickstart_port => '7122',
    :kickstart_timeout => '1200',
    :kickstart_file => 'preseed.cfg',
    :ssh_login_timeout => '1200',
    :ssh_user => 'root',
    :ssh_password => 'password',
    :ssh_key => '',
    :ssh_host_port => '7222',
    :ssh_guest_port => '22',
    :sudo_cmd => "echo '%p'|sudo -S sh '%f'",
    :shutdown_cmd => 'halt -p',
    :postinstall_files => [
        # basic minimal vm creation
        'build_time.sh',
        'apt_upgrade.sh',
        'configure_grub.sh',
        'configure_locale.sh',
        'configure_login.sh',
        'configure_networking.sh',
        'configure_acpid.sh',
        # turning it into a systemvm
        'install_systemvm_packages.sh',
        'configure_conntrack.sh',
        'configure_systemvm_services.sh',
        # cleanup & space-saving
        'cleanup.sh',
        'zerodisk.sh'
    ],
    :postinstall_timeout => '1200'
}

config.merge! architectures[arch.to_sym]

Veewee::Definition.declare(config)
