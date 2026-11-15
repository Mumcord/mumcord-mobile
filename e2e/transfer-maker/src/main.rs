// SPDX-License-Identifier: AGPL-3.0-only

//! The desktop side of "Send to your phone" for the end-to-end tests (e2e/run.sh): the phone
//! apps and the bridge can only read a transfer, so this makes one with the engine's own code,
//! as the desktop app does. It runs on the test machine only and is part of no app.
//!
//! Every run makes a new identity and a new code; nothing of it is kept anywhere. Usage:
//! `transfer-maker <server address> <server port>`. It prints, one per line:
//!
//! - the code,
//! - the text of every QR code of the transfer,
//! - `EXPIRED`,
//! - the text of one QR code of a transfer that is older than its two minutes.
//!
//! e2e/run.sh hands this to the test as an argument and never writes it to a file or a log.

use std::error::Error;

use mumcord_store::Identity;
use mumcord_store::transfer::{
    Transfer, TransferIdentity, TransferPayload, TransferServer, VALIDITY_SECS, unix_now,
};

/// The label of the identity and the name of the server in the transfer; the test looks for them.
const IDENTITY_LABEL: &str = "e2e-desktop";
const SERVER_NAME: &str = "e2e-transferred";
/// How much older than its validity the expired transfer is, in seconds.
const EXPIRED_BY_SECS: u64 = 60;

fn main() -> Result<(), Box<dyn Error>> {
    let mut arguments = std::env::args().skip(1);
    let address = arguments.next().ok_or("the server address is missing")?;
    let port: u16 = arguments
        .next()
        .ok_or("the server port is missing")?
        .parse()?;

    let identity = Identity::generate(IDENTITY_LABEL)?;
    let payload = TransferPayload {
        identities: vec![TransferIdentity::new(
            IDENTITY_LABEL,
            identity.cert_der(),
            identity.key_pkcs8_der(),
            true,
        )],
        servers: Some(vec![TransferServer {
            name: SERVER_NAME.to_owned(),
            address,
            port,
            username: IDENTITY_LABEL.to_owned(),
            identity_fingerprint: None,
        }]),
    };
    let now = unix_now();
    let transfer = Transfer::create(&payload, now)?;
    let expired = Transfer::create(&payload, now - VALIDITY_SECS - EXPIRED_BY_SECS)?;

    println!("{}", transfer.code());
    for part in transfer.parts() {
        println!("{part}");
    }
    println!("EXPIRED");
    let old = expired.parts().first().ok_or("the expired transfer has no part")?;
    println!("{old}");
    Ok(())
}
