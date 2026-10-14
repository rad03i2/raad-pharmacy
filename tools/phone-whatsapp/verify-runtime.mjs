import { Curve, initAuthCreds, proto } from '@whiskeysockets/baileys';
const pair = Curve.generateKeyPair();
if (pair.public.length !== 32 || pair.private.length !== 32 || !initAuthCreds().noiseKey) throw new Error('Crypto runtime unavailable');
const bytes = proto.Message.encode(proto.Message.create({ conversation: 'local runtime verification' })).finish();
if (!bytes.length) throw new Error('Protobuf runtime unavailable');
console.log('Baileys cryptography and message encoding loaded; no connection or message was sent.');
