package edu.vu.groupcast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.HashSet;
import java.util.logging.Logger;

public class ClientConnection extends Thread {
  private static final Logger LOG =
    Logger.getLogger(Thread.currentThread().getStackTrace()[0].getClassName());

  private static final int STATUS_ERROR = 0;
  private static final int STATUS_OK = 1;
  private static final int MSG = 2;

  Server mServer;
  Socket mClientSocket;
  String mName;
  boolean mRunning;
  BufferedReader mInStream;
  PrintStream mOutStream;

  public ClientConnection(Server server, Socket clientSocket) throws IOException {
    mServer = server;
    mClientSocket = clientSocket;
    mInStream = new BufferedReader(new InputStreamReader(mClientSocket.getInputStream()));
    mOutStream = new PrintStream(mClientSocket.getOutputStream());
    mRunning = false;
  }

  synchronized public void sendMsg(int type, String msg) {
    if (type == STATUS_OK) {
      mOutStream.println("+OK," + msg);
    }
    else if (type == STATUS_ERROR) {
      mOutStream.println("+ERROR," + msg);
    }
    else if (type == MSG) {
      mOutStream.println("+MSG," + msg);
    }
  }

  @Override
  public void run() {
    mRunning = true;
    try {
      //			sendMsg(STATUS_OK, mServer.toString());

      while (mRunning) {
        LOG.info(mClientSocket.getRemoteSocketAddress().toString() + ": Waiting for input from client");
        String msg = mInStream.readLine();

        if (msg == null) { // client closed the connection
          break;
        }

        // String[] tokens = msg.trim().split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
        String[] tokens = msg.split(",");

        StringBuilder sb1 = new StringBuilder();
        for (String t : tokens) {
          sb1.append(t);
          sb1.append(',');
        }

        if (sb1.length() > 0) {
          sb1.deleteCharAt(sb1.length() - 1); // remove last comma
        }

        LOG.info(mClientSocket.getRemoteSocketAddress().toString() + ": read :" + sb1.toString());

        // check if there's a valid command
        if (tokens.length == 0) {
          sendMsg(STATUS_ERROR, "No command given");
        }
        else {
          String cmd = tokens[0].trim();

          // handle BYE
          if ("BYE".equalsIgnoreCase(cmd)) {
            sendMsg(STATUS_OK, "BYE");
            break;
          }
          // handle VERSION
          else if ("VERSION".equalsIgnoreCase(cmd)) {
            sendMsg(STATUS_OK, "VERSION," + mServer.toString());
          }
          // handle NAME
          else if ("NAME".equalsIgnoreCase(cmd)) {
            if (mName != null) {
              sendMsg(STATUS_ERROR, "NAME: already set");
            }
            else if (tokens.length < 2) {
              sendMsg(STATUS_ERROR, "NAME: not specified");

            }
            else if (tokens[1].startsWith("@")) {
              sendMsg(STATUS_ERROR, "NAME: cannot start with @");
            }
            else {
              try {
                mServer.addClient(tokens[1], this);
                mName = tokens[1];
                sendMsg(STATUS_OK, "NAME," + mName);
              } catch (ClientNameException e) {
                sendMsg(STATUS_ERROR, "NAME," + tokens[1] + ": already in use");
              }
            }
          }

          // handle LIST
          else if ("LIST".equalsIgnoreCase(cmd)) {
            if (tokens.length < 2) {
              sendMsg(STATUS_ERROR, "LIST: parameter missing");
            }
            else {
              if ("USERS".equalsIgnoreCase(tokens[1])) {
                if (tokens.length == 2) {
                  // list all users

                  StringBuilder sb = new StringBuilder();
                  synchronized (mServer.mClients) {
                    for (String clientName: mServer.mClients.keySet()) {
                      sb.append(clientName);
                      sb.append(',');
                    }
                  }

                  if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                  }

                  sendMsg(STATUS_OK, "LIST,USERS:" + sb.toString());
                }
                else {
                  // list users in specified group
                  String groupName = tokens[2];
                  Group g = mServer.getGroupByName(groupName);
                  if (g == null) {
                    sendMsg(STATUS_ERROR, "LIST,USERS," + groupName + ": group not found");
                  }
                  else {
                    StringBuilder sb = new StringBuilder();
                    synchronized (g.mMembers) {
                      for (ClientConnection member: g.mMembers) {
                        sb.append(member.mName);
                        sb.append(',');
                      }
                    }

                    if (sb.length() > 0) {
                      sb.deleteCharAt(sb.length() - 1);
                    }

                    sendMsg(STATUS_OK, "LIST,USERS," + groupName + ':' + sb.toString());
                  }
                }
              }
              else if("GROUPS".equalsIgnoreCase(tokens[1])) {
                // list groups
                StringBuilder sb = new StringBuilder();
                synchronized (mServer.mGroups) {
                  for (Group group: mServer.mGroups.values()) {
                    sb.append(group.toString());
                    sb.append(',');
                  }
                }

                if (sb.length() > 0) {
                  sb.deleteCharAt(sb.length() - 1);
                }

                sendMsg(STATUS_OK, "LIST,GROUPS:" + sb.toString());
              }
              else if("MYGROUPS".equalsIgnoreCase(tokens[1])) {
                // list my group memberships
                StringBuilder sb = new StringBuilder();
                synchronized (mServer.mGroups) {
                  for (Group group: mServer.mGroups.values()) {
                    synchronized (group.mMembers) {
                      if (group.mMembers.contains(this)) {
                        sb.append(group.toString());
                        sb.append(',');
                      }
                    }
                  }
                }

                if (sb.length() > 0) {
                  sb.deleteCharAt(sb.length() - 1);
                }

                sendMsg(STATUS_OK, "LIST,MYGROUPS:" + sb.toString());
              }
              else {
                sendMsg(STATUS_ERROR, "LIST: Invalid parameter: " + tokens[1]);
              }
            }
          }

          // handle JOIN
          else if ("JOIN".equalsIgnoreCase(cmd)) {
            if (mName == null) {
              sendMsg(STATUS_ERROR, "JOIN: name not set");
            }
            else if (tokens.length < 2) {
              sendMsg(STATUS_ERROR, "JOIN: no group given");
            }
            else if (!tokens[1].startsWith("@")) {
              sendMsg(STATUS_ERROR, "JOIN: group must start with @");
            }
            else {
              String groupName = tokens[1];

              try {
                int maxMembers = 0;
                if (tokens.length > 2) {
                  maxMembers = Integer.parseInt(tokens[2]);
                }
                Group group = mServer.joinGroup(groupName, this, maxMembers);
                sendMsg(STATUS_OK, "JOIN," + group.toString());
              } catch (GroupFullException e) {
                sendMsg(STATUS_ERROR, "JOIN," + groupName
                + ": group is full");
              } catch (NumberFormatException e) {
                sendMsg(STATUS_ERROR,
                "JOIN," + groupName + ": invalid maximum group size");
              } catch (MaxMembersMismatchException e) {
                sendMsg(STATUS_ERROR,
                "JOIN," + groupName + ": maximum group size mismatch with existing group");
              }
            }
          }

          // handle QUIT
          else if ("QUIT".equalsIgnoreCase(cmd)) {
            if (tokens.length < 2) {
              sendMsg(STATUS_ERROR, "QUIT: no group given");
            }

            else {
              String groupName = tokens[1];

              try {
                mServer.quitGroup(groupName, this);
                sendMsg(STATUS_OK, "QUIT," + groupName);
              } catch (NoSuchGroupException e) {
                sendMsg(STATUS_ERROR, "QUIT," + groupName + ": group does not exist");
              } catch (NonMemberException e) {
                sendMsg(STATUS_ERROR, "QUIT," + groupName + ": client is not a member");
              }
            }
          }

          // handle MSG
          else if ("MSG".equalsIgnoreCase(cmd)) {
            if (mName == null) {
              sendMsg(STATUS_ERROR, "MSG: name not set");
            }
            else if (tokens.length < 2) {
              sendMsg(STATUS_ERROR, "MSG: no address given");
            }
            else if (tokens.length < 3) {
              sendMsg(STATUS_ERROR, "MSG: message body empty");
            }

            else {
              String address = tokens[1];

              StringBuilder sb = new StringBuilder();
              for (int i = 2; i < tokens.length; i++) {
                sb.append(tokens[i]);
                sb.append(',');
              }

              if (sb.length() > 0) {
                sb.deleteCharAt(sb.length() - 1);
              }

              String body = sb.toString();

              HashSet<ClientConnection> dsts = new HashSet<ClientConnection>();
              Group g = mServer.getGroupByName(address);

              if (g != null) {
                LOG.info("Found group " + address + ": " + g.toString());
                synchronized (g.mMembers) {
                  dsts.addAll(g.mMembers);
                }
              } else {
                LOG.info("Group " + address + " not found");
              }

              ClientConnection client = mServer.getClientByName(address);
              if (client != null) {
                dsts.add(client);
              }
              dsts.remove(this);

              if (!dsts.isEmpty()) {
                int cnt = 0;
                for (ClientConnection c : dsts) {
                  c.sendMsg(MSG, mName + "," + address + "," + body);

                  if (!c.mOutStream.checkError()) {
                    cnt++;
                  }
                  else {
                    // error writing socket output stream: close it
                    c.mRunning = false;
                    c.mOutStream.close();
                    // this will implicitly remove the client and its singleton groups
                  }
                }
                sendMsg(STATUS_OK, "MSG," + address + "," + body + ": " + cnt + " client(s) notified");
              } else {
                sendMsg(STATUS_ERROR, "MSG," + address + "," + body + ": no recipients found");
              }
            }
          }

          else {
            sendMsg(STATUS_ERROR, "Invalid command (" + cmd + ")");
          }
        }
      }

    } catch (IOException e) {
      if (mRunning) {
        // only print error if the client was supposed to be running, i.e. it wasn't stopped explicitly
        e.printStackTrace();
      }
    } finally {
      // make sure client is removed from server's data structures
      mServer.removeClient(this);

      try {
        mInStream.close();
      } catch (IOException e) {}
      mOutStream.close();
      try {
        mClientSocket.close();
      } catch (IOException e) {}

      LOG.info("Client connection terminated");
    }
  }
}
