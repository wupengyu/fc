const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const tempBase = os.tmpdir();
const tempDir = path.join(tempBase, 'xmind_full_' + Date.now());

// 完整的湖南GR关系网
const content = [
  {
    'id': 'root',
    'class': 'sheet',
    'title': '展视网湖南区域政府事务关系网',
    'rootTopic': {
      'id': 'main',
      'class': 'topic',
      'title': '展视网湖南GR关系网',
      'children': {
        'attached': [
          // 一、省级支持力量
          {
            'id': 't1',
            'title': '一、省级支持力量',
            'children': {
              'attached': [
                {
                  'id': 't1-1',
                  'title': '省央企合作对接暨重大招商引资驻京工作专班',
                  'children': {
                    'attached': [
                      {
                        'id': 't1-1-1',
                        'title': '李学斌 ★★★',
                        'labels': ['正常履职'],
                        'children': {
                          'attached': [
                            { 'id': 't1-1-1a', 'title': '职务：省驻京专班副主任' },
                            { 'id': 't1-1-1b', 'title': '沟通要点：可协助外部资源引入' }
                          ]
                        }
                      },
                      {
                        'id': 't1-1-2',
                        'title': '梁亚 ★★★',
                        'labels': ['多次参与我司会议'],
                        'children': {
                          'attached': [
                            { 'id': 't1-1-2a', 'title': '职务：省驻京专班一组成员' },
                            { 'id': 't1-1-2b', 'title': '沟通要点：保持联系，传递信息' }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          // 二、长沙湘江新区
          {
            'id': 't2',
            'title': '二、长沙湘江新区（核心融资与总部落地）',
            'children': {
              'attached': [
                // 2.1 新区决策层
                {
                  'id': 't2-1',
                  'title': '2.1 新区决策层',
                  'children': {
                    'attached': [
                      {
                        'id': 't2-1-1',
                        'title': '周健 ★★★★★',
                        'labels': ['最高决策者'],
                        'children': {
                          'attached': [
                            { 'id': 't2-1-1a', 'title': '职务：长沙市委副书记、湘江新区党工委书记、岳麓区委书记' },
                            { 'id': 't2-1-1b', 'title': '动态：2026年2月主持民主生活会' },
                            { 'id': 't2-1-1c', 'title': '沟通要点：签约仪式争取出席' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-2',
                        'title': '邹特 ★★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-1-2a', 'title': '职务：湘江新区管委会主任、岳麓区区长' },
                            { 'id': 't2-1-2b', 'title': '动态：2026年1月部署"三大任务"' },
                            { 'id': 't2-1-2c', 'title': '沟通要点：主管行政、财政金融' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-3',
                        'title': '张毅 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-1-3a', 'title': '职务：湘江新区党工委副书记、管委会副主任' },
                            { 'id': 't2-1-3b', 'title': '沟通要点：分管湘江集团' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-4',
                        'title': '帅军 ★★★★★',
                        'labels': ['已建立直接联系'],
                        'children': {
                          'attached': [
                            { 'id': 't2-1-4a', 'title': '职务：湘江新区党工委委员、管委会副主任' },
                            { 'id': 't2-1-4b', 'title': '沟通要点：可定期汇报，争取签约出席' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-5',
                        'title': '王先民 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-1-5a', 'title': '职务：湘江新区党工委委员、管委会副主任' },
                            { 'id': 't2-1-5b', 'title': '沟通要点：分管科技创新和产业促进局' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-6',
                        'title': '任明 ★★★',
                        'labels': ['新面孔'],
                        'children': {
                          'attached': [
                            { 'id': 't2-1-6a', 'title': '职务：湘江新区党工委委员' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-7',
                        'title': '王玮 ★★★',
                        'labels': ['新面孔'],
                        'children': {
                          'attached': [
                            { 'id': 't2-1-7a', 'title': '职务：湘江新区党工委委员' }
                          ]
                        }
                      },
                      {
                        'id': 't2-1-8',
                        'title': '谭海 ★★★',
                        'labels': ['新面孔'],
                        'children': {
                          'attached': [
                            { 'id': 't2-1-8a', 'title': '职务：湘江新区党工委委员' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 2.2 湘江国投
                {
                  'id': 't2-2',
                  'title': '2.2 湘江国投（融资核心）',
                  'labels': ['注册资本80亿', '基金规模765亿'],
                  'children': {
                    'attached': [
                      {
                        'id': 't2-2-0',
                        'title': '【公司概况】',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-0a', 'title': '注册资本：80亿元' },
                            { 'id': 't2-2-0b', 'title': '自主管理基金规模：252亿元' },
                            { 'id': 't2-2-0c', 'title': '母子基金总规模：765亿元' },
                            { 'id': 't2-2-0d', 'title': '累计投资项目：1160个' },
                            { 'id': 't2-2-0e', 'title': '培育上市企业：56家' },
                            { 'id': 't2-2-0f', 'title': '培育独角兽：25家' },
                            { 'id': 't2-2-0g', 'title': '湘江基金小镇：943家基金企业，规模4005亿' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-1',
                        'title': '龚国旺 ★★★★★',
                        'labels': ['最终决策者'],
                        'children': {
                          'attached': [
                            { 'id': 't2-2-1a', 'title': '职务：党总支书记、董事长' },
                            { 'id': 't2-2-1b', 'title': '关联：有接触' },
                            { 'id': 't2-2-1c', 'title': '动态：2026年1月巡察整改会议' },
                            { 'id': 't2-2-1d', 'title': '沟通要点：签约争取出席' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-2',
                        'title': '范清泉 ★★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-2a', 'title': '职务：董事、总经理' },
                            { 'id': 't2-2-2b', 'title': '关联：2025年4月参与投资意向沟通' },
                            { 'id': 't2-2-2c', 'title': '沟通要点：负责日常经营，跟进尽调' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-3',
                        'title': '🔴 皮聪 ★★★★★',
                        'labels': ['当前重点对接', '核心攻坚层'],
                        'children': {
                          'attached': [
                            { 'id': 't2-2-3a', 'title': '职务：副总经理' },
                            { 'id': 't2-2-3b', 'title': '关联：分管投资业务，有接触' },
                            { 'id': 't2-2-3c', 'title': '沟通要点：跟进尽调结果' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-4',
                        'title': '周蕊 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-4a', 'title': '职务：董事、常务副总经理' },
                            { 'id': 't2-2-4b', 'title': '关联：未接触' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-5',
                        'title': '吴汉颖 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-5a', 'title': '职务：副总经理' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-6',
                        'title': '娄鹏飞 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-6a', 'title': '职务：副总经理' }
                          ]
                        }
                      },
                      {
                        'id': 't2-2-7',
                        'title': '王继辉 ★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-2-7a', 'title': '职务：纪检组长' },
                            { 'id': 't2-2-7b', 'title': '沟通要点：注意合规沟通' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 2.3 长沙信息产业园
                {
                  'id': 't2-3',
                  'title': '2.3 长沙信息产业园（总部落地执行）',
                  'children': {
                    'attached': [
                      {
                        'id': 't2-3-1',
                        'title': '夏河年 ★★★★★',
                        'labels': ['已建立高层联系'],
                        'children': {
                          'attached': [
                            { 'id': 't2-3-1a', 'title': '职务：党工委副书记、管委会主任；新区新一代信息技术产业链办主任' },
                            { 'id': 't2-3-1b', 'title': '动态：2025年12月带队赴北京走访展视网' },
                            { 'id': 't2-3-1c', 'title': '沟通要点：汇报落地进展，争取支持' }
                          ]
                        }
                      },
                      {
                        'id': 't2-3-2',
                        'title': '傅纯 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-3-2a', 'title': '职务：党工委委员、长沙软件园发展中心主任' },
                            { 'id': 't2-3-2b', 'title': '动态：2025年12月参与北京走访' },
                            { 'id': 't2-3-2c', 'title': '沟通要点：继续对接落户、人才政策' }
                          ]
                        }
                      },
                      {
                        'id': 't2-3-3',
                        'title': '🔴 廖紫君 ★★★★★',
                        'labels': ['直接对接人', '核心攻坚层'],
                        'children': {
                          'attached': [
                            { 'id': 't2-3-3a', 'title': '职务：长沙信息产业片区管理办公室主任' },
                            { 'id': 't2-3-3b', 'title': '动态：2026年1月主持我司走访会议' },
                            { 'id': 't2-3-3c', 'title': '沟通要点：总部办公场地落实' }
                          ]
                        }
                      },
                      {
                        'id': 't2-3-4',
                        'title': '🔴 周东 ★★★★★',
                        'labels': ['核心攻坚层'],
                        'children': {
                          'attached': [
                            { 'id': 't2-3-4a', 'title': '职务：招商合作二局局长' },
                            { 'id': 't2-3-4b', 'title': '沟通要点：继续对接办公场地' }
                          ]
                        }
                      },
                      {
                        'id': 't2-3-5',
                        'title': '🔴 李茜 ★★★★★',
                        'labels': ['核心攻坚层'],
                        'children': {
                          'attached': [
                            { 'id': 't2-3-5a', 'title': '职务：招商合作二局副局长' },
                            { 'id': 't2-3-5b', 'title': '沟通要点：继续跟进' }
                          ]
                        }
                      },
                      {
                        'id': 't2-3-6',
                        'title': '方永强 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-3-6a', 'title': '职务：长沙软件园有限公司法定代表人' },
                            { 'id': 't2-3-6b', 'title': '沟通要点：涉及园区平台合作时可对接' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 2.4 关联支持部门
                {
                  'id': 't2-4',
                  'title': '2.4 关联支持部门',
                  'children': {
                    'attached': [
                      {
                        'id': 't2-4-1',
                        'title': '李玮玮 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-4-1a', 'title': '职务：财政金融局局长' },
                            { 'id': 't2-4-1b', 'title': '沟通要点：涉及政府引导基金审批' }
                          ]
                        }
                      },
                      {
                        'id': 't2-4-2',
                        'title': '曾敏 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-4-2a', 'title': '职务：科技创新和产业促进局局长' },
                            { 'id': 't2-4-2b', 'title': '沟通要点：可对接研发补贴、项目申报' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 2.5 关联平台公司
                {
                  'id': 't2-5',
                  'title': '2.5 关联平台公司',
                  'children': {
                    'attached': [
                      {
                        'id': 't2-5-1',
                        'title': '张利刚 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-5-1a', 'title': '职务：湘江集团党委书记、董事长' },
                            { 'id': 't2-5-1b', 'title': '沟通要点：可争取项目合作' }
                          ]
                        }
                      },
                      {
                        'id': 't2-5-2',
                        'title': '李志军 ★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-5-2a', 'title': '职务：湘江集团党委副书记、董事、总经理' }
                          ]
                        }
                      },
                      {
                        'id': 't2-5-3',
                        'title': '张奕 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't2-5-3a', 'title': '职务：长沙麓谷资本管理有限公司投资总监' },
                            { 'id': 't2-5-3b', 'title': '关联：有接触' },
                            { 'id': 't2-5-3c', 'title': '动态：2025年4月明确"社会资本领投、政府跟投40%"' },
                            { 'id': 't2-5-3d', 'title': '沟通要点：继续跟进投资意向' }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          // 三、娄底市
          {
            'id': 't3',
            'title': '三、娄底市（工厂选址·乡情大本营）',
            'labels': ['乡情资源', "材料谷"],
            'children': {
              'attached': [
                // 核心优势
                {
                  'id': 't3-0',
                  'title': '【核心优势】',
                  'children': {
                    'attached': [
                      { 'id': 't3-0a', 'title': '乡情：您与伍岳总裁均为新化籍' },
                      { 'id': 't3-0b', 'title': '材料谷：硅钢产能300万吨/年（全国第四，占16%）' },
                      { 'id': 't3-0c', 'title': '建筑政策：装配式建筑占比66.15%（全省第一）' },
                      { 'id': 't3-0d', 'title': '空间保障：冷水江经开区扩区469公顷，总976.72公顷' }
                    ]
                  }
                },
                // 3.1 市级决策层
                {
                  'id': 't3-1',
                  'title': '3.1 市级决策层',
                  'children': {
                    'attached': [
                      {
                        'id': 't3-1-1',
                        'title': '🟣 何朝晖 ★★★★★',
                        'labels': ['乡情纽带', '乡情突破层'],
                        'children': {
                          'attached': [
                            { 'id': 't3-1-1a', 'title': '职务：市委副书记、市长' },
                            { 'id': 't3-1-1b', 'title': '关联：有接触，乡情纽带' },
                            { 'id': 't3-1-1c', 'title': '动态：2026年政府工作报告强调"材料谷"建设' },
                            { 'id': 't3-1-1d', 'title': '沟通要点：乡情大旗，新化籍企业家返乡投资标杆' }
                          ]
                        }
                      },
                      {
                        'id': 't3-1-2',
                        'title': '🟢 傅小松 ★★★★',
                        'labels': ['新任命', '待拜访层'],
                        'children': {
                          'attached': [
                            { 'id': 't3-1-2a', 'title': '职务：副市长' },
                            { 'id': 't3-1-2b', 'title': '关联：未接触' },
                            { 'id': 't3-1-2c', 'title': '动态：2025年12月任命；2026年1月调研科技局，分管科技创新' },
                            { 'id': 't3-1-2d', 'title': '沟通要点：分管科技，需建立联系' }
                          ]
                        }
                      },
                      {
                        'id': 't3-1-3',
                        'title': '杨维 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-1-3a', 'title': '职务：副市长' },
                            { 'id': 't3-1-3b', 'title': '关联：有接触' },
                            { 'id': 't3-1-3c', 'title': '沟通要点：保持联系' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 3.2 招商引资直接窗口
                {
                  'id': 't3-2',
                  'title': '3.2 招商引资直接窗口',
                  'children': {
                    'attached': [
                      {
                        'id': 't3-2-1',
                        'title': '🔴 周新辉 ★★★★★',
                        'labels': ['乡情纽带', '核心攻坚层'],
                        'children': {
                          'attached': [
                            { 'id': 't3-2-1a', 'title': '职务：市商务局党组书记、局长' },
                            { 'id': 't3-2-1b', 'title': '关联：直接对接，乡情纽带' },
                            { 'id': 't3-2-1c', 'title': '沟通要点：返乡投资直接窗口，协调项目落地' }
                          ]
                        }
                      },
                      {
                        'id': 't3-2-2',
                        'title': '市商务局办公室 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-2-2a', 'title': '联系电话：0738-8223563' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 3.3 产业落地执行部门
                {
                  'id': 't3-3',
                  'title': '3.3 产业落地执行部门',
                  'children': {
                    'attached': [
                      {
                        'id': 't3-3-1',
                        'title': '🟠 吴润民 ★★★★★',
                        'labels': ['工厂选址层', '未接触'],
                        'children': {
                          'attached': [
                            { 'id': 't3-3-1a', 'title': '职务：市住建局党组书记、局长' },
                            { 'id': 't3-3-1b', 'title': '动态：2026年1月主持住建局座谈会，装配式建筑占比66.15%（全省第一）' },
                            { 'id': 't3-3-1c', 'title': '沟通要点：核心政策部门，对接新型建筑工业化政策' }
                          ]
                        }
                      },
                      {
                        'id': 't3-3-2',
                        'title': '🟢 易俊 ★★★★',
                        'labels': ['待拜访层'],
                        'children': {
                          'attached': [
                            { 'id': 't3-3-2a', 'title': '职务：市科技局党组书记、局长' },
                            { 'id': 't3-3-2b', 'title': '关联：未接触' },
                            { 'id': 't3-3-2c', 'title': '沟通要点：对接研发补贴、高新技术企业认定' }
                          ]
                        }
                      },
                      {
                        'id': 't3-3-3',
                        'title': '刘辉 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-3-3a', 'title': '职务：市人力资源和社会保障局局长' },
                            { 'id': 't3-3-3b', 'title': '动态：2025年12月任命' },
                            { 'id': 't3-3-3c', 'title': '沟通要点：对接人才引进政策' }
                          ]
                        }
                      },
                      {
                        'id': 't3-3-4',
                        'title': '（待补充）市工信局局长 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-3-4a', 'title': '沟通要点：对接"三电"产业链配套' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 3.4 娄底经开区
                {
                  'id': 't3-4',
                  'title': '3.4 娄底经开区（国家级·工厂选址核心）',
                  'labels': ['硅钢产值300亿', '三电项目51个'],
                  'children': {
                    'attached': [
                      {
                        'id': 't3-4-0',
                        'title': '【园区概况】',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-0a', 'title': '硅钢产业年产值：突破300亿元' },
                            { 'id': 't3-4-0b', 'title': '2025年引进"三电"等项目：51个' },
                            { 'id': 't3-4-0c', 'title': '引资额：超过207亿元' },
                            { 'id': 't3-4-0d', 'title': '技工贸总收入：1644亿元' },
                            { 'id': 't3-4-0e', 'title': '地方财政收入：44%高位增长' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-1',
                        'title': '🟠 肖雄杰 ★★★★★',
                        'labels': ['工厂选址层', '需建立联系'],
                        'children': {
                          'attached': [
                            { 'id': 't3-4-1a', 'title': '职务：娄底经开区党工委书记' },
                            { 'id': 't3-4-1b', 'title': '关联：未接触' },
                            { 'id': 't3-4-1c', 'title': '动态：2026年1月主持经济工作务虚会；2月主持企业家座谈会，强调"三钢三电一钛"产业链' },
                            { 'id': 't3-4-1d', 'title': '沟通要点：工厂选址首选，需安排拜访' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-2',
                        'title': '🟠 王寄忠 ★★★★★',
                        'labels': ['工厂选址层'],
                        'children': {
                          'attached': [
                            { 'id': 't3-4-2a', 'title': '职务：娄底经开区党工委副书记、管委会主任' },
                            { 'id': 't3-4-2b', 'title': '关联：未接触' },
                            { 'id': 't3-4-2c', 'title': '沟通要点：考察园区配套、标准厂房' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-3',
                        'title': '朱石牛 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-3a', 'title': '职务：娄底经开区领导' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-4',
                        'title': '蔡斌 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-4a', 'title': '职务：娄底经开区领导' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-5',
                        'title': '肖红 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-5a', 'title': '职务：娄底经开区领导' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-6',
                        'title': '周佩辉 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-6a', 'title': '职务：娄底经开区领导' }
                          ]
                        }
                      },
                      {
                        'id': 't3-4-7',
                        'title': '姚枫 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-4-7a', 'title': '职务：娄底经开区领导' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 3.5 冷水江经开区
                {
                  'id': 't3-5',
                  'title': '3.5 冷水江经开区（扩区机遇）',
                  'labels': ['新扩469公顷', '总976.72公顷'],
                  'children': {
                    'attached': [
                      {
                        'id': 't3-5-1',
                        'title': '（待补充）党工委书记 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-5-1a', 'title': '动态：2026年2月扩区获批' },
                            { 'id': 't3-5-1b', 'title': '沟通要点：扩区机遇，承接产业转移' }
                          ]
                        }
                      },
                      {
                        'id': 't3-5-2',
                        'title': '（待补充）管委会主任 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-5-2a', 'title': '沟通要点：考察用地条件' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 3.6 娄星产业开发区
                {
                  'id': 't3-6',
                  'title': '3.6 娄星产业开发区（备选）',
                  'children': {
                    'attached': [
                      {
                        'id': 't3-6-1',
                        'title': '（待补充）负责人 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't3-6-1a', 'title': '动态：2024年12月公司曾考察' },
                            { 'id': 't3-6-1b', 'title': '沟通要点：保持联系，作为备选' }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          // 四、生产基地备选区域
          {
            'id': 't4',
            'title': '四、生产基地备选区域',
            'children': {
              'attached': [
                // 4.1 益阳高新区
                {
                  'id': 't4-1',
                  'title': '4.1 益阳高新区（乡情资源）',
                  'labels': ['工业税收增长23.05%'],
                  'children': {
                    'attached': [
                      {
                        'id': 't4-1-1',
                        'title': '🟣 左志锋 ★★★★★',
                        'labels': ['同乡渊源', '乡情突破层'],
                        'children': {
                          'attached': [
                            { 'id': 't4-1-1a', 'title': '职务：党工委书记' },
                            { 'id': 't4-1-1b', 'title': '关联：有接触，同乡渊源（新化籍，曾任新化父母官）' },
                            { 'id': 't4-1-1c', 'title': '动态：2026年1月主持重点项目调度会' },
                            { 'id': 't4-1-1d', 'title': '沟通要点：乡情突破，以新化同乡身份建立深度信任' }
                          ]
                        }
                      },
                      {
                        'id': 't4-1-2',
                        'title': '李俊杰 ★★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-1-2a', 'title': '职务：党工委副书记、管委会主任' },
                            { 'id': 't4-1-2b', 'title': '关联：多次座谈，明确支持' },
                            { 'id': 't4-1-2c', 'title': '沟通要点：保持沟通' }
                          ]
                        }
                      },
                      {
                        'id': 't4-1-3',
                        'title': '🟢 谭罗垠 ★★★★',
                        'labels': ['待拜访层'],
                        'children': {
                          'attached': [
                            { 'id': 't4-1-3a', 'title': '职务：党工委副书记、管委会副主任' },
                            { 'id': 't4-1-3b', 'title': '动态：2026年2月部署重点项目建设工作' },
                            { 'id': 't4-1-3c', 'title': '沟通要点：建议由左书记引荐' }
                          ]
                        }
                      },
                      {
                        'id': 't4-1-4',
                        'title': '喻理科 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-1-4a', 'title': '职务：党工委委员、管委会副主任' }
                          ]
                        }
                      },
                      {
                        'id': 't4-1-5',
                        'title': '龚志勇 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-1-5a', 'title': '职务：党工委委员、管委会副主任' },
                            { 'id': 't4-1-5b', 'title': '关联：参与4月27日座谈' }
                          ]
                        }
                      },
                      {
                        'id': 't4-1-6',
                        'title': '熊宇宏 ★★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-1-6a', 'title': '职务：经济合作局局长' },
                            { 'id': 't4-1-6b', 'title': '关联：多次对接' },
                            { 'id': 't4-1-6c', 'title': '沟通要点：具体落地协调' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 4.2 湘阴高新区
                {
                  'id': 't4-2',
                  'title': '4.2 湘阴高新区',
                  'children': {
                    'attached': [
                      {
                        'id': 't4-2-1',
                        'title': '焦洪桥 ★★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-2-1a', 'title': '职务：湘阴高新区党工委书记' },
                            { 'id': 't4-2-1b', 'title': '关联：多次主持座谈' },
                            { 'id': 't4-2-1c', 'title': '沟通要点：保持沟通，跟进场地' }
                          ]
                        }
                      },
                      {
                        'id': 't4-2-2',
                        'title': '王海英 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-2-2a', 'title': '职务：湘阴县商务粮食局局长' }
                          ]
                        }
                      },
                      {
                        'id': 't4-2-3',
                        'title': '魏小春 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-2-3a', 'title': '职务：临港产投党支部副书记、总经理' },
                            { 'id': 't4-2-3b', 'title': '沟通要点：投资资源对接' }
                          ]
                        }
                      }
                    ]
                  }
                },
                // 4.3 湘潭高新区
                {
                  'id': 't4-3',
                  'title': '4.3 湘潭高新区',
                  'children': {
                    'attached': [
                      {
                        'id': 't4-3-1',
                        'title': '王新平 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-3-1a', 'title': '职务：党工委委员、副主任' },
                            { 'id': 't4-3-1b', 'title': '关联：参与中关村考察座谈' }
                          ]
                        }
                      },
                      {
                        'id': 't4-3-2',
                        'title': '文平 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't4-3-2a', 'title': '职务：经济合作局局长' }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          // 五、战略客户与生态伙伴
          {
            'id': 't5',
            'title': '五、战略客户与生态伙伴',
            'children': {
              'attached': [
                {
                  'id': 't5-1',
                  'title': '中建五局（核心战略客户）',
                  'children': {
                    'attached': [
                      {
                        'id': 't5-1-1',
                        'title': '🟢 王永锋 ★★★★★',
                        'labels': ['待拜访层'],
                        'children': {
                          'attached': [
                            { 'id': 't5-1-1a', 'title': '职务：副总经理、总工程师' },
                            { 'id': 't5-1-1b', 'title': '关联：未接触' },
                            { 'id': 't5-1-1c', 'title': '动态：2026年1月赴广西大学调研智能建造、绿色低碳' },
                            { 'id': 't5-1-1d', 'title': '沟通要点：智能建造领域负责人，需重点拜访' }
                          ]
                        }
                      },
                      {
                        'id': 't5-1-2',
                        'title': '李凯 ★★★★',
                        'children': {
                          'attached': [
                            { 'id': 't5-1-2a', 'title': '职务：副总经理' },
                            { 'id': 't5-1-2b', 'title': '沟通要点：负责业务布局，可同步建立联系' }
                          ]
                        }
                      },
                      {
                        'id': 't5-1-3',
                        'title': '程经理 ★★★',
                        'children': {
                          'attached': [
                            { 'id': 't5-1-3a', 'title': '职务：企划人力部（总承包公司）' },
                            { 'id': 't5-1-3b', 'title': '关联：有联系方式' },
                            { 'id': 't5-1-3c', 'title': '沟通要点：对接校企合作、产教融合' }
                          ]
                        }
                      }
                    ]
                  }
                }
              ]
            }
          },
          // 六、关系网层级说明
          {
            'id': 't6',
            'title': '六、关系网层级说明（拜访优先级）',
            'children': {
              'attached': [
                {
                  'id': 't6-1',
                  'title': '🔴 核心攻坚层',
                  'children': {
                    'attached': [
                      { 'id': 't6-1a', 'title': '皮聪（湘江国投）' },
                      { 'id': 't6-1b', 'title': '廖紫君（信息产业园）' },
                      { 'id': 't6-1c', 'title': '周东/李茜（招商局）' },
                      { 'id': 't6-1d', 'title': '周新辉（娄底商务局）' }
                    ]
                  }
                },
                {
                  'id': 't6-2',
                  'title': '🟣 乡情突破层',
                  'children': {
                    'attached': [
                      { 'id': 't6-2a', 'title': '左志锋（益阳书记）' },
                      { 'id': 't6-2b', 'title': '何朝晖（娄底市长）' }
                    ]
                  }
                },
                {
                  'id': 't6-3',
                  'title': '🟠 工厂选址层',
                  'children': {
                    'attached': [
                      { 'id': 't6-3a', 'title': '肖雄杰（娄底经开区书记）' },
                      { 'id': 't6-3b', 'title': '王寄忠（娄底经开区主任）' },
                      { 'id': 't6-3c', 'title': '吴润民（娄底住建局）' }
                    ]
                  }
                },
                {
                  'id': 't6-4',
                  'title': '🟡 高层支持层',
                  'children': {
                    'attached': [
                      { 'id': 't6-4a', 'title': '周健（新区书记）' },
                      { 'id': 't6-4b', 'title': '邹特（新区主任）' },
                      { 'id': 't6-4c', 'title': '帅军（新区副主任）' },
                      { 'id': 't6-4d', 'title': '夏河年（信息产业园主任）' },
                      { 'id': 't6-4e', 'title': '龚国旺（湘江国投董事长）' }
                    ]
                  }
                },
                {
                  'id': 't6-5',
                  'title': '🟢 待拜访层',
                  'children': {
                    'attached': [
                      { 'id': 't6-5a', 'title': '傅小松（娄底副市长）' },
                      { 'id': 't6-5b', 'title': '易俊（娄底科技局）' },
                      { 'id': 't6-5c', 'title': '谭罗垠（益阳副主任）' },
                      { 'id': 't6-5d', 'title': '王永锋（中建五局总工）' }
                    ]
                  }
                }
              ]
            }
          },
          // 七、行动建议
          {
            'id': 't7',
            'title': '七、当前阶段重点行动建议',
            'children': {
              'attached': [
                {
                  'id': 't7-1',
                  'title': '立即行动（2月24-28日）',
                  'children': {
                    'attached': [
                      { 'id': 't7-1a', 'title': '1. 皮聪：跟进尽调结果，了解投决会时间表' },
                      { 'id': 't7-1b', 'title': '2. 廖紫君：确认总部办公场地交付时间' },
                      { 'id': 't7-1c', 'title': '3. 周新辉：以乡情纽带致电，预约3月上旬拜访' }
                    ]
                  }
                },
                {
                  'id': 't7-2',
                  'title': '3月拜访计划',
                  'children': {
                    'attached': [
                      { 'id': 't7-2a', 'title': '3月上旬：周新辉（娄底商务局）- 首次正式拜访' },
                      { 'id': 't7-2b', 'title': '3月中旬：肖雄杰/王寄忠（娄底经开区）- 考察园区' },
                      { 'id': 't7-2c', 'title': '3月中旬：吴润民（娄底住建局）- 对接建筑政策' },
                      { 'id': 't7-2d', 'title': '3月下旬：左志锋（益阳书记）- 同乡拜访' }
                    ]
                  }
                }
              ]
            }
          }
        ]
      }
    }
  }
];

const metadata = {
  'creator': {
    'name': 'Claude',
    'version': '1.0.0'
  }
};

const manifest = {
  'file-entries': {
    'content.json': {},
    'metadata.json': {}
  }
};

// Create temp directory
fs.mkdirSync(tempDir, { recursive: true });

// Write files
fs.writeFileSync(path.join(tempDir, 'content.json'), JSON.stringify(content, null, 2), 'utf8');
fs.writeFileSync(path.join(tempDir, 'metadata.json'), JSON.stringify(metadata, null, 2), 'utf8');
fs.writeFileSync(path.join(tempDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8');

// Output paths
const zipFile = path.join(tempBase, 'hunan-gr-network-full.zip');
const outputFile = path.join(tempBase, 'hunan-gr-network-full.xmind');

// Remove existing files if exist
if (fs.existsSync(outputFile)) fs.unlinkSync(outputFile);
if (fs.existsSync(zipFile)) fs.unlinkSync(zipFile);

// Use PowerShell to create zip
try {
  const psCommand = `Compress-Archive -Path "${tempDir}\\*" -DestinationPath "${zipFile}" -Force`;
  execSync(`powershell -Command "${psCommand}"`, { stdio: 'pipe' });

  // Rename .zip to .xmind
  if (fs.existsSync(zipFile)) {
    fs.renameSync(zipFile, outputFile);
    console.log('SUCCESS');
    console.log('FILE_PATH:' + outputFile);
  }
} catch (e) {
  console.error('Error creating zip:', e.message);
}

// Cleanup temp directory
try {
  fs.rmSync(tempDir, { recursive: true, force: true });
} catch (e) {}
